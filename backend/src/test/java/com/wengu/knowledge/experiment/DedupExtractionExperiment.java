package com.wengu.knowledge.experiment;

import com.wengu.NoDatabase;
import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.service.DedupContext;
import com.wengu.knowledge.service.ExtractionValidator;
import com.wengu.knowledge.service.KnowledgeExtractionService;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 去重上下文实验（#16，决策 014）：给模型一批已有知识点，再抽取一组新题，检查它能否认出已有的内容、
 * 又不把相关但不同的内容误判成已有。会真实调用模型、产生费用，默认跳过：
 * <pre>
 * .\mvnw.cmd test "-Dtest=DedupExtractionExperiment" "-Dllm.experiment=true"
 * </pre>
 * 数据在 data-private/samples/dedup/（私人数据，已被 git 忽略）：
 * <ul>
 *   <li>existing-titles.txt：已有知识点的标题，一行一个，按顺序编号为 E1、E2……；# 开头的行是注释</li>
 *   <li>new-questions.json：新题和预期结果。expect 为 COVERED（完全覆盖）、PARTIAL（部分覆盖）、NEW（不该判为已有）</li>
 * </ul>
 * 跑几次由 -Ddedup.runs 指定，默认 3。结果写入 data-private/experiments/dedup-时间戳-模型名/。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY})
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class DedupExtractionExperiment {

    private static final Path DATA = Path.of("..", "data-private");
    private static final Path SAMPLES = DATA.resolve("samples/dedup");

    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    private KnowledgeExtractionService extractionService;

    /** 一道新题和预期结果。existing 为预期引用的已有知识点编号。 */
    record NewQuestion(int id, String text, String expect, List<String> existing, String note) {
    }

    @Test
    void 带去重上下文抽取新题() throws IOException {
        List<String> titles = Files.readAllLines(SAMPLES.resolve("existing-titles.txt")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        DedupContext context = DedupContext.of(
                IntStream.range(0, titles.size()).mapToObj(i -> new DedupContext.Existing(i + 1, titles.get(i))).toList(),
                List.of());
        List<NewQuestion> newQuestions = List.of(json.readValue(
                Files.readString(SAMPLES.resolve("new-questions.json")), NewQuestion[].class));
        List<ExtractionQuestion> questions = newQuestions.stream()
                .map(q -> new ExtractionQuestion(q.id(), q.text()))
                .toList();
        int runs = Integer.getInteger("dedup.runs", 3);

        Path dir = null;
        List<Map<String, Object>> summary = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            long start = System.nanoTime();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("run", run);
            LlmResult<?> raw;
            ExtractionResult result = null;
            try {
                LlmResult<ExtractionResult> r = extractionService.extract(questions, context);
                raw = r;
                result = r.content();
                meta.put("validationProblems", ExtractionValidator.validate(questions, context, result));
            } catch (LlmOutputParseException e) {
                raw = e.getResult();
                meta.put("error", e.getMessage());
            }
            meta.put("durationSeconds", Math.round((System.nanoTime() - start) / 1e8) / 10.0);
            meta.put("model", raw.model());
            meta.put("promptTokens", raw.promptTokens());
            meta.put("completionTokens", raw.completionTokens());
            if (result != null) {
                meta.put("knowledgePointCount", result.knowledgePoints() == null ? 0 : result.knowledgePoints().size());
                meta.putAll(score(newQuestions, result));
            }
            if (dir == null) {
                dir = outputDir(raw.model());
            }
            write(dir.resolve("run" + run + "-raw.txt"), raw.rawText());
            if (result != null) {
                write(dir.resolve("run" + run + "-result.json"), json.writeValueAsString(result));
            }
            summary.add(meta);
            System.out.println("[去重实验] " + meta);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("time", LocalDateTime.now().toString());
        meta.put("promptVersion", KnowledgeExtractionService.PROMPT_VERSION);
        meta.put("temperature", KnowledgeExtractionService.TEMPERATURE);
        meta.put("existingCount", context.entries().size());
        meta.put("questionCount", questions.size());
        meta.put("results", summary);
        write(dir.resolve("summary.json"), json.writeValueAsString(meta));
        System.out.println("[去重实验] 结果目录 " + dir.toAbsolutePath().normalize());
    }

    /**
     * 按预期给每道题打分：
     * COVERED 要写进 covered、引用预期的编号，并且不再输出成新知识点；
     * PARTIAL 要写进 covered，同时还有新知识点；NEW 不能写进 covered。
     */
    static Map<String, Object> score(List<NewQuestion> newQuestions, ExtractionResult result) {
        List<CoveredQuestion> covered = result.covered() == null ? List.of() : result.covered();
        Map<Integer, List<String>> coveredLabels = covered.stream()
                .collect(Collectors.toMap(CoveredQuestion::questionId,
                        c -> c.existingIds() == null ? List.of() : c.existingIds(), (a, b) -> a));
        Set<Integer> inNewPoints = (result.knowledgePoints() == null ? List.<ExtractedKnowledgePoint>of()
                : result.knowledgePoints()).stream()
                .flatMap(kp -> kp.sourceQuestionIds() == null ? Stream.<Integer>empty()
                        : kp.sourceQuestionIds().stream())
                .collect(Collectors.toSet());

        Map<String, int[]> counts = new LinkedHashMap<>();
        List<String> mistakes = new ArrayList<>();
        for (NewQuestion q : newQuestions) {
            boolean isCovered = coveredLabels.containsKey(q.id());
            boolean isNew = inNewPoints.contains(q.id());
            List<String> labels = coveredLabels.getOrDefault(q.id(), List.of());
            boolean correct = switch (q.expect()) {
                case "COVERED" -> isCovered && !isNew && labels.containsAll(q.existing());
                case "PARTIAL" -> isCovered && isNew;
                default -> !isCovered;
            };
            int[] c = counts.computeIfAbsent(q.expect(), k -> new int[2]);
            c[1]++;
            if (correct) {
                c[0]++;
            } else {
                mistakes.add("第 " + q.id() + " 题（预期 " + q.expect() + " " + q.existing() + "）实际："
                        + (isCovered ? "covered " + labels : "未标已有") + (isNew ? "，有新知识点" : "，没有新知识点")
                        + " — " + q.text());
            }
        }
        Map<String, Object> score = new LinkedHashMap<>();
        counts.forEach((k, v) -> score.put(k, v[0] + "/" + v[1]));
        score.put("mistakes", mistakes);
        return score;
    }

    private static Path outputDir(String model) throws IOException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeModel = model == null ? "unknown" : model.replaceAll("[^A-Za-z0-9._-]", "_");
        return Files.createDirectories(DATA.resolve("experiments").resolve("dedup-" + time + "-" + safeModel));
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
