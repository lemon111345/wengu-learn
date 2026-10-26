package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    private static final List<ExtractionQuestion> QUESTIONS = List.of(
            new ExtractionQuestion(1, "说说 TCP 的四次挥手？"),
            new ExtractionQuestion(7, "为什么 TCP 挥手需要有 TIME_WAIT 状态？"));

    private static ExtractedKnowledgePoint kp(int order, String title, Integer... ids) {
        return new ExtractedKnowledgePoint(order, title, "要点", KnowledgePointType.MECHANISM, List.of(ids));
    }

    @Test
    void 合格的结果没有问题() {
        ExtractionResult result = new ExtractionResult(List.of(), List.of(
                kp(1, "TCP 四次挥手的过程是怎样的？", 1),
                kp(2, "为什么需要 TIME_WAIT 状态？", 1, 7)));

        assertThat(ExtractionValidator.validate(QUESTIONS, result)).isEmpty();
    }

    @Test
    void 发现漏题和编造的题号() {
        ExtractionResult result = new ExtractionResult(List.of(), List.of(kp(1, "TCP 四次挥手的过程是怎样的？", 1, 99)));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("来源题号 99 不存在"))
                .anyMatch(p -> p.contains("没有对应的知识点：[7]"));
    }

    @Test
    void 发现编号不连续和标题重复() {
        ExtractionResult result = new ExtractionResult(List.of(), List.of(
                kp(1, "TCP 四次挥手的过程是怎样的？", 1),
                kp(3, "TCP 四次挥手的过程是怎样的？", 7)));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("标题与其他知识点重复"))
                .anyMatch(p -> p.contains("连续编号"));
    }

    @Test
    void 发现缺少类型和来源() {
        ExtractionResult result = new ExtractionResult(List.of(), List.of(
                new ExtractedKnowledgePoint(1, "什么是 TIME_WAIT？", "要点", null, List.of(1, 7)),
                new ExtractedKnowledgePoint(2, "TCP 四次挥手的过程是怎样的？", "要点", KnowledgePointType.MECHANISM, List.of())));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("缺少类型"))
                .anyMatch(p -> p.contains("没有来源题号"));
    }

    @Test
    void 空结果() {
        assertThat(ExtractionValidator.validate(QUESTIONS, new ExtractionResult(List.of(), List.of())))
                .containsExactly("没有抽取出任何知识点");
    }

    @Test
    void 题目列表格式() {
        assertThat(KnowledgeExtractionService.formatQuestions(QUESTIONS))
                .startsWith("以下是 2 道面试题")
                .contains("[1] 说说 TCP 的四次挥手？\n[7] 为什么 TCP 挥手需要有 TIME_WAIT 状态？");
    }
}
