package com.wengu.knowledge.experiment;

import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentHelpersTest {

    @Test
    void 解析样本题标题忽略其他内容() {
        String markdown = """
                # 样本

                <!-- 说明 -->

                ## 1. 说说 TCP 的四次挥手？

                参考答案（不使用）

                ## 81. 为什么 TCP 挥手需要有 TIME_WAIT 状态？
                ### 3. 三级标题不是题目
                """;

        assertThat(SampleQuestions.parse(markdown)).containsExactly(
                new ExtractionQuestion(1, "说说 TCP 的四次挥手？"),
                new ExtractionQuestion(81, "为什么 TCP 挥手需要有 TIME_WAIT 状态？"));
    }

    @Test
    void 标注表按学习顺序输出且正确转义() {
        List<ExtractionQuestion> questions = List.of(
                new ExtractionQuestion(1, "说说 TCP 的四次挥手？"),
                new ExtractionQuestion(81, "为什么需要 TIME_WAIT？"));
        ExtractionResult result = new ExtractionResult(List.of(), List.of(
                new ExtractedKnowledgePoint(2, "为什么需要 \"TIME_WAIT\"？", "等待 2MSL, 防止旧报文", KnowledgePointType.CONCEPT, List.of(1, 81)),
                new ExtractedKnowledgePoint(1, "四次挥手的过程？", "四个报文", KnowledgePointType.MECHANISM, List.of(1))));

        String csv = AnnotationCsv.render(questions, result);
        String[] lines = csv.substring(AnnotationCsv.BOM.length()).split("\r\n(?=\")");

        assertThat(csv).startsWith(AnnotationCsv.BOM);
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("\"学习顺序\",\"标题\"");
        assertThat(lines[1]).startsWith("\"1\",\"四次挥手的过程？\"").contains("\"MECHANISM（原理机制）\"");
        // 双引号转义为两个、逗号保留在引号内、多个来源题目用换行分隔
        assertThat(lines[2])
                .contains("\"为什么需要 \"\"TIME_WAIT\"\"？\"")
                .contains("\"等待 2MSL, 防止旧报文\"")
                .contains("\"1, 81\"")
                .contains("\"[1] 说说 TCP 的四次挥手？\n[81] 为什么需要 TIME_WAIT？\"");
    }
}
