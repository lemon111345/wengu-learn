package com.wengu.knowledge.experiment;

import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 知识点抽取实验（issue #3）：读取 data-private 中的样本题，调用模型抽取知识点，结果写入
 * data-private/experiments/时间戳-模型名/ 供人工标注。会真实调用模型、产生费用，默认跳过：
 * <pre>
 * .\mvnw.cmd test "-Dtest=KnowledgeExtractionExperiment" "-Dllm.experiment=true"
 * </pre>
 * 换模型：设置 WENGU_LLM_MODEL 环境变量。样本题和结果都是私人数据，只放在 data-private（已被 git 忽略）。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class KnowledgeExtractionExperiment {

    /** 测试从 backend 目录运行，data-private 在仓库根目录。 */
    private static final Path DATA = Path.of("..", "data-private");
    private static final Path SAMPLES = DATA.resolve("samples/questions.md");

    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    private KnowledgeExtractionService extractionService;

    @Test
    void 抽取样本题的知识点() throws IOException {
        List<ExtractionQuestion> questions = SampleQuestions.parse(Files.readString(SAMPLES));
        assertFalse(questions.isEmpty(), "没有读到样本题：" + SAMPLES.toAbsolutePath().normalize());

        Instant start = Instant.now();
        LlmResult<ExtractionResult> result;
        try {
            result = extractionService.extract(questions);
        } catch (LlmOutputParseException e) {
            Path dir = outputDir(e.getResult().model());
            write(dir.resolve("raw.txt"), e.getResult().rawText());
            write(dir.resolve("meta.json"), json.writeValueAsString(meta(questions, e.getResult(), start, List.of(), e.getMessage())));
            fail(e.getMessage() + "，原始输出已保存到 " + dir.toAbsolutePath().normalize());
            return;
        }

        List<String> problems = ExtractionValidator.validate(questions, result.content());
        Path dir = outputDir(result.model());
        write(dir.resolve("raw.txt"), result.rawText());
        write(dir.resolve("result.json"), json.writeValueAsString(result.content()));
        write(dir.resolve("meta.json"), json.writeValueAsString(meta(questions, result, start, problems, null)));
        write(dir.resolve("annotate.csv"), AnnotationCsv.render(questions, result.content()));

        System.out.println("[抽取实验] " + questions.size() + " 道题 → " + result.content().knowledgePoints().size()
                + " 个知识点，模型 " + result.model() + "，结束原因 " + result.finishReason()
                + "，token " + result.promptTokens() + " + " + result.completionTokens());
        System.out.println("[抽取实验] 校验问题 " + problems.size() + " 个" + (problems.isEmpty() ? "" : "：\n  " + String.join("\n  ", problems)));
        System.out.println("[抽取实验] 结果目录 " + dir.toAbsolutePath().normalize());
    }

    private Map<String, Object> meta(List<ExtractionQuestion> questions, LlmResult<?> result, Instant start,
                                     List<String> problems, String error) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("time", LocalDateTime.now().toString());
        meta.put("model", result.model());
        meta.put("promptVersion", KnowledgeExtractionService.PROMPT_VERSION);
        meta.put("questionCount", questions.size());
        meta.put("knowledgePointCount", result.content() instanceof ExtractionResult r && r.knowledgePoints() != null
                ? r.knowledgePoints().size() : null);
        meta.put("durationSeconds", Duration.between(start, Instant.now()).toSeconds());
        meta.put("finishReason", result.finishReason());
        meta.put("promptTokens", result.promptTokens());
        meta.put("completionTokens", result.completionTokens());
        meta.put("validationProblems", problems);
        meta.put("error", error);
        return meta;
    }

    private static Path outputDir(String model) throws IOException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeModel = model == null ? "unknown" : model.replaceAll("[^A-Za-z0-9._-]", "_");
        return Files.createDirectories(DATA.resolve("experiments").resolve(time + "-" + safeModel));
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
