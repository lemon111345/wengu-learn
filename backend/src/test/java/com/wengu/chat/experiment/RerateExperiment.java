package com.wengu.chat.experiment;

import com.wengu.NoDatabase;
import com.wengu.WenguApplication;
import com.wengu.chat.experiment.ChatExperimentSupport.ParsedTranscript;
import com.wengu.chat.experiment.RatingSuggestion.Rating;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 离线重评实验（issue #14）：不重新对话，用已有的对话记录重新调用评分建议，对比不同版本的评分 prompt。
 * 对话固定、只换 prompt，才能看出 prompt 本身的效果；每个版本评 RUNS 次，看温度 0.2 下结果有多大波动。
 * <p>
 * 读取 data-private/experiments/chat-* 下所有的 NN-transcript.md，知识点的要点按标题从 samples 下的两份知识点列表中查找。
 * 结果写入 data-private/experiments/rerate-时间戳-模型名/：ratings.csv 是每份记录各次的评分，details.md 是每次的完整输出。
 * <p>
 * 会真实调用模型、产生费用（记录数 × 版本数 × RUNS 次）。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.experiment.RerateExperiment"
 * </pre>
 */
public class RerateExperiment {

    /** 参与对比的 prompt 版本及其输出格式。旧版本用当时的输出格式，和当时评分的条件一致。 */
    static final List<Version> VERSIONS = List.of(
            new Version("v2", SuggestionV2.class),
            new Version("v3", RatingSuggestion.class));

    static final int RUNS = 3;

    private static final Path DATA = Path.of("..", "data-private");
    private static final Path EXPERIMENTS = DATA.resolve("experiments");
    private static final List<Path> KNOWLEDGE_POINT_FILES = List.of(
            DATA.resolve("samples/chat-kps.json"), DATA.resolve("samples/chat-kps-v1.json"));

    /** v2 的输出格式：v3 起 RatingSuggestion 多了 outOfScopeGaps，重跑 v2 时仍用当时的三个字段。 */
    record SuggestionV2(String analysis, Rating rating, List<String> weakPoints) {
    }

    /** 评分 prompt 的版本号（对应 prompts/rating-suggestion-版本号.md）和它的输出格式。 */
    record Version(String name, Class<?> outputType) {
    }

    private final LlmService llm;
    private final String model;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Path outputDir;

    /** 各版本累计的 token 数，键如“v2.promptTokens”。 */
    private final Map<String, Long> tokens = new LinkedHashMap<>();

    RerateExperiment(LlmService llm, String model) {
        this.llm = llm;
        this.model = model;
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        this.outputDir = EXPERIMENTS.resolve("rerate-" + time + "-" + model.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    public static void main(String[] args) throws IOException {
        SpringApplication app = new SpringApplication(WenguApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN", NoDatabase.EXCLUDE_KEY, NoDatabase.EXCLUDE_VALUE,
                NoDatabase.LAZY_KEY, "true", NoDatabase.NO_RECOVERY_KEY, "false"));
        try (ConfigurableApplicationContext context = app.run(args)) {
            String model = context.getEnvironment().getProperty("spring.ai.openai.chat.options.model", "unknown");
            new RerateExperiment(context.getBean(LlmService.class), model).run();
        }
    }

    void run() throws IOException {
        Map<String, ChatKnowledgePoint> kps = loadKnowledgePoints();
        List<Path> transcripts = findTranscripts();
        List<String> versionNames = VERSIONS.stream().map(Version::name).toList();
        Map<String, String> prompts = new HashMap<>();
        versionNames.forEach(v -> prompts.put(v, LearningChatExperiment.loadPrompt("prompts/rating-suggestion-" + v + ".md")));

        Files.createDirectories(outputDir);
        Path csv = outputDir.resolve("ratings.csv");
        Path details = outputDir.resolve("details.md");
        List<String> header = new ArrayList<>(List.of("知识点", "来源", "自评", "当时的 AI 评分"));
        for (String v : versionNames) {
            for (int run = 1; run <= RUNS; run++) {
                header.add(v + " 第 " + run + " 次");
            }
        }
        Files.writeString(csv, ChatExperimentSupport.BOM + ChatExperimentSupport.csvLine(header) + "\r\n", StandardCharsets.UTF_8);
        Files.writeString(details, "# 离线重评明细\n", StandardCharsets.UTF_8);

        System.out.println("模型 " + model + "，对话记录 " + transcripts.size() + " 份，版本 " + versionNames + "，各 " + RUNS + " 次");
        System.out.println("结果目录：" + outputDir.toAbsolutePath().normalize());
        int total = transcripts.size() * VERSIONS.size() * RUNS;
        int done = 0;
        List<String> sources = new ArrayList<>();

        for (Path file : transcripts) {
            String source = EXPERIMENTS.relativize(file).toString().replace('\\', '/');
            ParsedTranscript transcript = ChatExperimentSupport.parseTranscript(Files.readString(file));
            ChatKnowledgePoint kp = kps.get(transcript.title());
            if (kp == null) {
                System.out.println("跳过（知识点列表中找不到这个标题）：" + source);
                continue;
            }
            sources.add(source);
            String input = ChatExperimentSupport.ratingInput(kp, transcript.dialogue());
            List<String> row = new ArrayList<>(List.of(
                    kp.title(), source, nameOf(transcript.selfRating()), nameOf(transcript.aiRating())));
            StringBuilder detail = new StringBuilder("\n## " + kp.title() + "\n\n- 来源：" + source
                    + "\n- 自评：" + nameOf(transcript.selfRating()) + "，当时的 AI 评分：" + nameOf(transcript.aiRating()) + "\n");

            for (Version version : VERSIONS) {
                for (int run = 1; run <= RUNS; run++) {
                    String heading = "\n### " + version.name() + " 第 " + run + " 次：";
                    String result;
                    try {
                        LlmResult<?> r = llm.call(prompts.get(version.name()), input, version.outputType(),
                                LearningChatExperiment.RATING_TEMPERATURE);
                        addTokens(version.name() + ".promptTokens", r.promptTokens());
                        addTokens(version.name() + ".completionTokens", r.completionTokens());
                        result = nameOf(ratingOf(r.content()));
                        detail.append(heading).append(result).append("\n\n```json\n")
                                .append(json.writeValueAsString(r.content())).append("\n```\n");
                    } catch (LlmOutputParseException e) {
                        result = "出错";
                        detail.append(heading).append("出错\n\n").append(e.getMessage())
                                .append("，原始输出：\n\n").append(e.getResult().rawText()).append('\n');
                    } catch (RuntimeException e) {
                        result = "出错";
                        detail.append(heading).append("出错\n\n").append(e).append('\n');
                    }
                    row.add(result);
                    done++;
                    System.out.printf("[%d/%d] %s %s 第 %d 次：%s%n", done, total, source, version.name(), run, result);
                }
            }
            // 每份记录评完就写入，中途出错也不会丢掉已经评完的
            Files.writeString(csv, ChatExperimentSupport.csvLine(row) + "\r\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Files.writeString(details, detail, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("time", LocalDateTime.now().toString());
        meta.put("model", model);
        meta.put("versions", versionNames);
        meta.put("runs", RUNS);
        meta.put("ratingTemperature", LearningChatExperiment.RATING_TEMPERATURE);
        meta.put("transcripts", sources);
        meta.put("tokens", tokens);
        Files.writeString(outputDir.resolve("meta.json"), json.writeValueAsString(meta), StandardCharsets.UTF_8);
        System.out.println("完成，结果在 " + outputDir.toAbsolutePath().normalize());
    }

    /** 两份知识点列表合并，按标题查找。 */
    private Map<String, ChatKnowledgePoint> loadKnowledgePoints() throws IOException {
        Map<String, ChatKnowledgePoint> kps = new HashMap<>();
        for (Path file : KNOWLEDGE_POINT_FILES) {
            List<ChatKnowledgePoint> list = json.readValue(Files.readString(file), new TypeReference<>() {
            });
            list.forEach(kp -> kps.putIfAbsent(kp.title(), kp));
        }
        return kps;
    }

    /** experiments 下以 chat- 开头的目录中的 NN-transcript.md，按路径排序，也就是按实验时间和知识点序号。 */
    private static List<Path> findTranscripts() throws IOException {
        try (Stream<Path> files = Files.walk(EXPERIMENTS, 2)) {
            return files
                    .filter(f -> f.getFileName().toString().matches("\\d{2}-transcript\\.md")
                            && f.getParent().getFileName().toString().startsWith("chat-"))
                    .sorted()
                    .toList();
        }
    }

    private void addTokens(String key, Integer value) {
        if (value != null) {
            tokens.merge(key, (long) value, Long::sum);
        }
    }

    private static Rating ratingOf(Object suggestion) {
        return switch (suggestion) {
            case RatingSuggestion s -> s.rating();
            case SuggestionV2 s -> s.rating();
            default -> throw new IllegalArgumentException("未知的输出格式：" + suggestion);
        };
    }

    private static String nameOf(Rating rating) {
        return rating == null ? "" : rating.name();
    }
}
