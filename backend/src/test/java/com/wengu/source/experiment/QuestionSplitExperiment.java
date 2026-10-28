package com.wengu.source.experiment;

import com.wengu.NoDatabase;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.service.QuestionSplitService;
import com.wengu.source.service.SplitValidator;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 题目切分实验（#15）：每份样本调用模型切分若干次，和预期结果比对，并检查多次结果是否一致。
 * 会真实调用模型、产生费用，默认跳过：
 * <pre>
 * .\mvnw.cmd test "-Dtest=QuestionSplitExperiment" "-Dllm.experiment=true"
 * </pre>
 * 样本有三处来源：
 * <ul>
 *   <li>src/test/resources/split-samples：自己写的样本，每份配一个 .expected.json</li>
 *   <li>data-private/split-samples：从真实网页复制的资料（.md 或 .txt），预期结果可选</li>
 *   <li>data-private/questions/all.md 的前 30 行题目，预期结果取表格的“题目”列</li>
 * </ul>
 * 结果写入 data-private/experiments/split-时间戳-模型名/。每份样本跑几次由 -Dsplit.runs 指定，默认 3。
 */
@SpringBootTest(properties = NoDatabase.PROPERTY)
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class QuestionSplitExperiment {

    private static final Path PUBLIC_SAMPLES = Path.of("src", "test", "resources", "split-samples");
    private static final Path DATA = Path.of("..", "data-private");
    private static final Path PRIVATE_SAMPLES = DATA.resolve("split-samples");
    private static final Path QUESTION_BANK = DATA.resolve("questions/all.md");
    private static final int QUESTION_BANK_ROWS = 30;

    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    private QuestionSplitService splitService;

    /** 一份样本：原文，以及预期的切分结果（没有时为 null，只看校验和稳定性）。 */
    record Sample(String name, String content, List<SplitQuestion> expected) {
    }

    @Test
    void 切分各种格式的样本() throws IOException {
        List<Sample> samples = loadSamples();
        assertFalse(samples.isEmpty(), "没有读到样本");
        int runs = Integer.getInteger("split.runs", 3);

        Path dir = null;
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Sample sample : samples) {
            List<List<SplitQuestion>> outputs = new ArrayList<>();
            for (int run = 1; run <= runs; run++) {
                long start = System.nanoTime();
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("sample", sample.name());
                meta.put("run", run);
                meta.put("contentLength", sample.content().length());
                LlmResult<?> raw;
                List<SplitQuestion> questions = null;
                try {
                    LlmResult<SplitResult> result = splitService.split(sample.content());
                    raw = result;
                    List<String> problems = SplitValidator.validate(sample.content(), result.content());
                    meta.put("validationProblems", problems);
                    if (problems.isEmpty()) {
                        questions = SplitValidator.deduplicate(result.content().questions());
                        meta.put("questionCount", questions.size());
                        meta.put("duplicatesRemoved", result.content().questions().size() - questions.size());
                        if (sample.expected() != null) {
                            meta.put("diff", diff(sample.expected(), questions));
                        }
                    }
                } catch (LlmOutputParseException e) {
                    raw = e.getResult();
                    meta.put("error", e.getMessage());
                } catch (RuntimeException e) {
                    raw = null;
                    meta.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                meta.put("durationSeconds", Math.round((System.nanoTime() - start) / 1e8) / 10.0);
                if (raw != null) {
                    meta.put("model", raw.model());
                    meta.put("finishReason", raw.finishReason());
                    meta.put("promptTokens", raw.promptTokens());
                    meta.put("completionTokens", raw.completionTokens());
                }
                if (dir == null) {
                    dir = outputDir(raw == null ? null : raw.model());
                }
                String prefix = sample.name() + "-run" + run;
                write(dir.resolve(prefix + "-raw.txt"), raw == null ? "" : raw.rawText());
                if (questions != null) {
                    write(dir.resolve(prefix + "-result.json"), json.writeValueAsString(questions));
                }
                outputs.add(questions);
                summary.add(meta);
                System.out.println("[切分实验] " + meta);
            }
            Map<String, Object> stability = new LinkedHashMap<>();
            stability.put("sample", sample.name());
            stability.put("allRunsIdentical", outputs.stream().map(QuestionSplitExperiment::normalized).distinct().count() == 1);
            summary.add(stability);
            System.out.println("[切分实验] " + stability);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("time", LocalDateTime.now().toString());
        meta.put("promptVersion", QuestionSplitService.PROMPT_VERSION);
        meta.put("temperature", QuestionSplitService.TEMPERATURE);
        meta.put("runsPerSample", runs);
        meta.put("results", summary);
        write(dir.resolve("summary.json"), json.writeValueAsString(meta));
        System.out.println("[切分实验] 结果目录 " + dir.toAbsolutePath().normalize());
    }

    /** 和预期结果比对：漏掉的题、多出来的题、追问对不上的题。比对前都做规范化。 */
    private static Map<String, Object> diff(List<SplitQuestion> expected, List<SplitQuestion> actual) {
        Map<String, List<String>> expectedByText = byText(expected);
        Map<String, List<String>> actualByText = byText(actual);
        List<String> missing = new ArrayList<>();
        List<String> wrongFollowUps = new ArrayList<>();
        for (SplitQuestion q : expected) {
            String key = SplitValidator.normalize(q.text());
            if (!actualByText.containsKey(key)) {
                missing.add(q.text());
            } else if (!actualByText.get(key).equals(expectedByText.get(key))) {
                wrongFollowUps.add(q.text() + " → 实际追问 " + actualByText.get(key));
            }
        }
        List<String> extra = actual.stream()
                .filter(q -> !expectedByText.containsKey(SplitValidator.normalize(q.text())))
                .map(SplitQuestion::text)
                .toList();
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("correct", missing.isEmpty() && extra.isEmpty() && wrongFollowUps.isEmpty());
        diff.put("missing", missing);
        diff.put("extra", extra);
        diff.put("wrongFollowUps", wrongFollowUps);
        return diff;
    }

    private static Map<String, List<String>> byText(List<SplitQuestion> questions) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (SplitQuestion q : questions) {
            List<String> followUps = q.followUps() == null ? List.of() : q.followUps();
            map.putIfAbsent(SplitValidator.normalize(q.text()), followUps.stream().map(SplitValidator::normalize).toList());
        }
        return map;
    }

    private static List<String> normalized(List<SplitQuestion> questions) {
        if (questions == null) {
            return null;
        }
        return questions.stream()
                .map(q -> SplitValidator.normalize(q.text()) + " | " + q.followUps().stream().map(SplitValidator::normalize).toList())
                .toList();
    }

    private List<Sample> loadSamples() throws IOException {
        List<Sample> samples = new ArrayList<>();
        samples.addAll(loadDir(PUBLIC_SAMPLES));
        if (Files.isDirectory(PRIVATE_SAMPLES)) {
            samples.addAll(loadDir(PRIVATE_SAMPLES));
        }
        if (Files.exists(QUESTION_BANK)) {
            samples.add(questionBankSample());
        }
        return samples;
    }

    private List<Sample> loadDir(Path dir) throws IOException {
        List<Sample> samples = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".expected.json") || !(fileName.endsWith(".md") || fileName.endsWith(".txt"))) {
                    continue;
                }
                String name = fileName.substring(0, fileName.lastIndexOf('.'));
                Path expectedFile = dir.resolve(name + ".expected.json");
                List<SplitQuestion> expected = Files.exists(expectedFile)
                        ? Arrays.asList(json.readValue(Files.readString(expectedFile), SplitQuestion[].class))
                        : null;
                samples.add(new Sample(name, Files.readString(file), expected));
            }
        }
        return samples;
    }

    /** 题库是 Markdown 表格：保留表格前的说明和表头，只取前 30 行题目，预期结果取第 2 列。 */
    private static Sample questionBankSample() throws IOException {
        List<String> lines = Files.readAllLines(QUESTION_BANK);
        List<String> kept = new ArrayList<>();
        List<SplitQuestion> expected = new ArrayList<>();
        int rows = 0;
        for (String line : lines) {
            boolean dataRow = line.matches("\\|\\s*\\d+\\s*\\|.*");
            if (dataRow && rows == QUESTION_BANK_ROWS) {
                break;
            }
            kept.add(line);
            if (dataRow) {
                rows++;
                expected.add(new SplitQuestion(line.split("\\|")[2].strip(), List.of()));
            }
        }
        return new Sample("all-md-first-" + QUESTION_BANK_ROWS, String.join("\n", kept), expected);
    }

    private static Path outputDir(String model) throws IOException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeModel = model == null ? "unknown" : model.replaceAll("[^A-Za-z0-9._-]", "_");
        return Files.createDirectories(DATA.resolve("experiments").resolve("split-" + time + "-" + safeModel));
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
