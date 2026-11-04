package com.wengu.source.experiment;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.wengu.NoDatabase;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.service.KnowledgeExtractionService;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.service.QuestionSplitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 文档导入实验（#26）：讲解型资料（笔记、文章）里没有现成的题目，现有的“识别题目”能不能用、要不要单独的 prompt。
 * <ol>
 *   <li>E1：自己写的线程池笔记（标题都不是问题）走现有的识别题目（question-split-v1）</li>
 *   <li>E2：同一份笔记、以及标题多是问题的 JavaGuide 集合文章，走“从内容生成题目”（question-generate-v1）</li>
 *   <li>E3：E2 生成的题目接着走现有的抽取（knowledge-extraction-v6），看知识点的粒度</li>
 * </ol>
 * 会真实调用模型、产生费用（约 8 次调用），默认跳过：
 * <pre>
 * .\mvnw.cmd test "-Dtest=DocumentImportExperiment" "-Dllm.experiment=true"
 * </pre>
 * 结果写入 data-private/experiments/docs-时间戳/。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY})
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class DocumentImportExperiment {

    private static final Path DATA = Path.of("..", "data-private");
    private static final Path NOTE = DATA.resolve("samples/doc-note-threadpool.md");
    private static final Path ARTICLE = DATA.resolve("split-samples/web-javaguide-collection.md");
    private static final int GENERATE_RUNS = 3;

    /** 生成的一道题：依据的小节、主问题、追问。section 放第一个，让模型先定位再出题 */
    @JsonPropertyOrder({"section", "text", "followUps"})
    record GeneratedQuestion(String section, String text, List<String> followUps) {
    }

    record GenerateResult(List<GeneratedQuestion> questions) {
    }

    @Autowired
    private LlmService llm;

    @Autowired
    private QuestionSplitService splitService;

    @Autowired
    private KnowledgeExtractionService extractionService;

    @Test
    void run() throws IOException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = DATA.resolve("experiments/docs-" + time);
        Files.createDirectories(out);
        String note = Files.readString(NOTE, StandardCharsets.UTF_8);
        String article = Files.readString(ARTICLE, StandardCharsets.UTF_8);
        String generatePrompt = new ClassPathResource("prompts/question-generate-v1.md").getContentAsString(StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("# 文档导入实验\n");

        // E1：笔记走现有的识别题目
        report.append("\n## E1 笔记 → 识别题目（split-v1）\n");
        for (int run = 1; run <= 2; run++) {
            try {
                LlmResult<SplitResult> r = splitService.split(note);
                List<String> texts = r.content().questions().stream().map(q -> q.text()).toList();
                report.append("\n第 ").append(run).append(" 次：").append(texts.size()).append(" 道\n");
                texts.forEach(t -> report.append("- ").append(t).append('\n'));
            } catch (RuntimeException e) {
                report.append("\n第 ").append(run).append(" 次失败：").append(e).append('\n');
            }
        }

        // E2：生成题目
        List<GeneratedQuestion> firstNoteRun = null;
        for (int run = 1; run <= GENERATE_RUNS; run++) {
            List<GeneratedQuestion> qs = generate(generatePrompt, note);
            if (run == 1) {
                firstNoteRun = qs;
            }
            appendGenerated(report, "E2 笔记 → 生成题目，第 " + run + " 次", qs);
        }
        appendGenerated(report, "E2 JavaGuide 集合文章 → 生成题目", generate(generatePrompt, article));

        // E3：生成的题目走现有的抽取
        report.append("\n## E3 笔记生成的题目（第 1 次）→ 抽取（extraction-v6）\n\n");
        List<GeneratedQuestion> qs = firstNoteRun;
        List<ExtractionQuestion> input = IntStream.range(0, qs.size())
                .mapToObj(i -> new ExtractionQuestion(i + 1, withFollowUps(qs.get(i))))
                .toList();
        LlmResult<ExtractionResult> extracted = extractionService.extract(input);
        List<ExtractedKnowledgePoint> kps = extracted.content().knowledgePoints();
        report.append(kps.size()).append(" 个知识点（").append(qs.size()).append(" 道题）\n\n");
        for (ExtractedKnowledgePoint kp : kps) {
            report.append("- [").append(kp.type()).append("] ").append(kp.title())
                    .append("（第 ").append(kp.sourceQuestionIds()).append(" 题）\n  要点：").append(kp.summary()).append('\n');
        }

        Files.writeString(out.resolve("report.md"), report, StandardCharsets.UTF_8);
        System.out.println("结果在 " + out.toAbsolutePath().normalize());
    }

    private List<GeneratedQuestion> generate(String prompt, String content) {
        return llm.call(prompt, "资料内容：\n\n" + content, GenerateResult.class, 0.2).content().questions();
    }

    private static void appendGenerated(StringBuilder report, String title, List<GeneratedQuestion> qs) {
        report.append("\n## ").append(title).append("：").append(qs.size()).append(" 道\n\n");
        List<String> sections = new ArrayList<>();
        for (GeneratedQuestion q : qs) {
            if (!sections.contains(q.section())) {
                sections.add(q.section());
            }
            report.append("- 【").append(q.section()).append("】").append(q.text());
            if (q.followUps() != null && !q.followUps().isEmpty()) {
                report.append("（追问：").append(String.join("；", q.followUps())).append("）");
            }
            report.append('\n');
        }
        report.append("\n涉及 ").append(sections.size()).append(" 节\n");
    }

    /** 和正式抽取的输入格式一致：追问拼在题目后面（决策 011） */
    private static String withFollowUps(GeneratedQuestion q) {
        return q.followUps() == null || q.followUps().isEmpty() ? q.text()
                : q.text() + "（追问：" + String.join("；", q.followUps()) + "）";
    }
}
