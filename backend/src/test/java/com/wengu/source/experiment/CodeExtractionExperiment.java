package com.wengu.source.experiment;

import com.wengu.NoDatabase;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * 源码抽取实验（issue #89）：用本项目的源码，按不同的选文件范围抽取个人经历类知识点。
 * 结果写入 data-private/experiments/code-时间戳-模型名/。会真实调用模型、产生费用，默认跳过。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd test "-Dtest=CodeExtractionExperiment" "-Dllm.experiment=true"
 * </pre>
 * 只跑部分组、改次数：-Dcode.sets=A,D -Dcode.runs=1。换模型：设置 WENGU_LLM_MODEL 环境变量。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY})
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class CodeExtractionExperiment {

    static final String PROMPT_VERSION = "v1";

    /** 和题目抽取一样用 0.2，结果要稳定。 */
    private static final double TEMPERATURE = 0.2;

    /** 测试从 backend 目录运行。 */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/wengu");
    private static final Path DATA = Path.of("..", "data-private");

    /** A 组：一次学习从发消息到评分入库的链路。 */
    private static final List<String> RATING_CHAIN = List.of(
            "chat/controller/SessionController.java",
            "chat/service/ChatService.java",
            "chat/service/StudySessionService.java",
            "chat/service/RatingService.java",
            "chat/service/RatingSuggestionService.java",
            "review/service/ReviewService.java");

    private static final Set<String> DATA_CLASS_DIRS = Set.of("dto", "entity", "mapper");

    private static final List<CodeSet> SETS = List.of(
            new CodeSet("A", RATING_CHAIN, Set.of(), false),
            new CodeSet("B", List.of("chat"), Set.of(), false),
            new CodeSet("B2", List.of("chat"), DATA_CLASS_DIRS, false),
            new CodeSet("C", List.of("chat", "review", "plan"), Set.of(), false),
            new CodeSet("D", RATING_CHAIN, Set.of(), true));

    /**
     * @param roots         相对 com/wengu 的文件或目录
     * @param excludedDirs  跳过这些名字的目录
     * @param stripComments 是否去掉注释，模拟没有详细注释的普通项目
     */
    private record CodeSet(String name, List<String> roots, Set<String> excludedDirs, boolean stripComments) {
    }

    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    private LlmService llm;

    @Test
    void 按不同的选文件范围抽取知识点() throws Exception {
        String prompt = new ClassPathResource("prompts/code-extraction-" + PROMPT_VERSION + ".md")
                .getContentAsString(StandardCharsets.UTF_8);
        Set<String> selected = Set.of(System.getProperty("code.sets", "A,B,B2,C,D").split(","));
        int runs = Integer.getInteger("code.runs", 2);
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outputDir = Files.createDirectories(DATA.resolve("experiments").resolve("code-" + time + "-deepseek"));

        // 并发跑：每次调用可能要一两分钟，顺序跑要等很久
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (CodeSet set : SETS) {
                if (!selected.contains(set.name())) {
                    continue;
                }
                Map<String, String> files = load(set);
                for (int run = 1; run <= runs; run++) {
                    int currentRun = run;
                    futures.add(pool.submit(() -> extract(prompt, set, currentRun, files, outputDir)));
                }
            }
        }

        List<Map<String, Object>> metas = new ArrayList<>();
        for (Future<Map<String, Object>> future : futures) {
            metas.add(future.get());
        }
        Files.writeString(outputDir.resolve("meta.json"), json.writeValueAsString(metas), StandardCharsets.UTF_8);
        metas.forEach(meta -> System.out.println("[源码抽取] " + meta));
        System.out.println("[源码抽取] 结果目录 " + outputDir.toAbsolutePath().normalize());
    }

    private Map<String, Object> extract(String prompt, CodeSet set, int run, Map<String, String> files, Path outputDir)
            throws IOException {
        Path dir = Files.createDirectories(outputDir.resolve(set.name() + "-" + run));
        String input = CodeExtractionSupport.renderInput(files);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("set", set.name());
        meta.put("run", run);
        meta.put("promptVersion", PROMPT_VERSION);
        meta.put("fileCount", files.size());
        meta.put("inputChars", input.length());
        Instant start = Instant.now();
        try {
            LlmResult<CodeExtractionResult> result = llm.call(prompt, input, CodeExtractionResult.class, TEMPERATURE);
            CodeExtractionResult content = result.content();
            List<String> problems = CodeExtractionSupport.checkEvidence(files, content);
            write(dir.resolve("raw.txt"), result.rawText());
            write(dir.resolve("result.json"), json.writeValueAsString(content));
            write(dir.resolve("annotate.csv"), CodeExtractionSupport.annotationCsv(set.name(), run, files, content));
            putUsage(meta, result);
            meta.put("knowledgePointCount", content.knowledgePoints().size());
            meta.put("evidenceCount", CodeExtractionSupport.evidenceCount(content));
            meta.put("evidenceProblems", problems);
        } catch (LlmOutputParseException e) {
            write(dir.resolve("raw.txt"), e.getResult().rawText());
            putUsage(meta, e.getResult());
            meta.put("error", e.getMessage());
        } catch (RuntimeException e) {
            meta.put("error", e.toString());
        }
        meta.put("durationSeconds", Duration.between(start, Instant.now()).toSeconds());
        write(dir.resolve("meta.json"), json.writeValueAsString(meta));
        return meta;
    }

    private static void putUsage(Map<String, Object> meta, LlmResult<?> result) {
        meta.put("model", result.model());
        meta.put("finishReason", result.finishReason());
        meta.put("promptTokens", result.promptTokens());
        meta.put("completionTokens", result.completionTokens());
    }

    /** 读出这一组的文件：键是相对 com/wengu 的路径，统一用 /，按路径排序。 */
    private static Map<String, String> load(CodeSet set) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (String root : set.roots()) {
            try (Stream<Path> paths = Files.walk(SOURCE_ROOT.resolve(root))) {
                List<Path> javaFiles = paths
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> Arrays.stream(SOURCE_ROOT.relativize(p).toString().split("[/\\\\]"))
                                .noneMatch(set.excludedDirs()::contains))
                        .sorted()
                        .toList();
                for (Path file : javaFiles) {
                    String key = SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                    String content = Files.readString(file);
                    files.put(key, set.stripComments() ? CodeExtractionSupport.stripComments(content) : content);
                }
            }
        }
        return files;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
