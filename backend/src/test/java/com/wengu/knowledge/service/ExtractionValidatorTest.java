package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CoveredQuestion;
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

    /** E1 为已有知识点，E2 为同一草稿中前一批的条目。 */
    private static final DedupContext CONTEXT = DedupContext.of(
            List.of(new DedupContext.Existing(11, "TCP 四次挥手的过程是怎样的？")),
            List.of(new DedupContext.Existing(22, "TCP 三次握手的过程是怎样的？")));

    private static ExtractedKnowledgePoint kp(int order, String title, Integer... ids) {
        return new ExtractedKnowledgePoint(order, title, "要点", KnowledgePointType.MECHANISM, List.of(ids));
    }

    private static ExtractionResult result(List<CoveredQuestion> covered, ExtractedKnowledgePoint... kps) {
        return new ExtractionResult(List.of(), covered, List.of(kps));
    }

    private static ExtractionResult result(ExtractedKnowledgePoint... kps) {
        return result(List.of(), kps);
    }

    private static CoveredQuestion covered(int questionId, String... existingIds) {
        return new CoveredQuestion(questionId, List.of(existingIds), "考的是同一内容");
    }

    @Test
    void 合格的结果没有问题() {
        ExtractionResult result = result(
                kp(1, "TCP 四次挥手的过程是怎样的？", 1),
                kp(2, "为什么需要 TIME_WAIT 状态？", 1, 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, result)).isEmpty();
    }

    @Test
    void 发现漏题和编造的题号() {
        ExtractionResult result = result(kp(1, "TCP 四次挥手的过程是怎样的？", 1, 99));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("来源题号 99 不存在"))
                .anyMatch(p -> p.contains("既没有对应的知识点，也没有标为已有：[7]"));
    }

    @Test
    void 发现编号不连续和标题重复() {
        ExtractionResult result = result(
                kp(1, "TCP 四次挥手的过程是怎样的？", 1),
                kp(3, "TCP 四次挥手的过程 是怎样的？", 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("标题与其他知识点重复"))
                .anyMatch(p -> p.contains("连续编号"));
    }

    @Test
    void 发现缺少类型和来源() {
        ExtractionResult result = result(
                new ExtractedKnowledgePoint(1, "什么是 TIME_WAIT？", "要点", null, List.of(1, 7)),
                new ExtractedKnowledgePoint(2, "TCP 四次挥手的过程是怎样的？", "要点", KnowledgePointType.MECHANISM, List.of()));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("缺少类型"))
                .anyMatch(p -> p.contains("没有来源题号"));
    }

    @Test
    void 空结果() {
        assertThat(ExtractionValidator.validate(QUESTIONS, result()))
                .containsExactly("以下题目既没有对应的知识点，也没有标为已有：[1, 7]");
        assertThat(ExtractionValidator.validate(QUESTIONS, null)).containsExactly("没有抽取结果");
    }

    @Test
    void 被已有知识点覆盖的题可以没有新知识点() {
        ExtractionResult result = result(List.of(covered(1, "E1")), kp(1, "为什么需要 TIME_WAIT 状态？", 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, CONTEXT, result)).isEmpty();
    }

    @Test
    void 所有题都被覆盖时可以没有新知识点() {
        ExtractionResult result = result(List.of(covered(1, "E1"), covered(7, "E1", "E2")));

        assertThat(ExtractionValidator.validate(QUESTIONS, CONTEXT, result)).isEmpty();
    }

    @Test
    void 部分覆盖的题同时出现在covered和新知识点中() {
        ExtractionResult result = result(List.of(covered(1, "E1")),
                kp(1, "TCP 挥手时为什么要等 2MSL？", 1, 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, CONTEXT, result)).isEmpty();
    }

    @Test
    void 发现编造的已有知识点编号和题号() {
        ExtractionResult result = result(List.of(covered(1, "E9"), covered(99, "E1"), covered(7)));

        assertThat(ExtractionValidator.validate(QUESTIONS, CONTEXT, result))
                .anyMatch(p -> p.contains("第 1 题：已有知识点编号 [E9] 不存在"))
                .anyMatch(p -> p.contains("第 99 题：题号不存在"))
                .anyMatch(p -> p.contains("第 7 题：没有写覆盖它的已有知识点编号"))
                // 引用无效的 covered 不算覆盖
                .anyMatch(p -> p.contains("也没有标为已有：[1, 7]"));
    }

    @Test
    void 没有上下文时不能引用已有知识点() {
        ExtractionResult result = result(List.of(covered(1, "E1")), kp(1, "为什么需要 TIME_WAIT 状态？", 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, result))
                .anyMatch(p -> p.contains("已有知识点编号 [E1] 不存在"));
    }

    @Test
    void 发现与已有标题完全相同的新知识点() {
        ExtractionResult result = result(
                kp(1, "TCP 四次挥手的过程是怎样的？", 1),
                kp(2, "TCP 三次握手的过程是怎样的？", 7));

        assertThat(ExtractionValidator.validate(QUESTIONS, CONTEXT, result))
                .anyMatch(p -> p.contains("标题与已有知识点 E1 相同"))
                .anyMatch(p -> p.contains("标题与已有知识点 E2 相同"));
    }

    @Test
    void 题目列表格式() {
        assertThat(KnowledgeExtractionService.formatQuestions(QUESTIONS, DedupContext.EMPTY))
                .startsWith("以下是 2 道面试题")
                .contains("[1] 说说 TCP 的四次挥手？\n[7] 为什么 TCP 挥手需要有 TIME_WAIT 状态？")
                .doesNotContain("已有的知识点");
    }

    @Test
    void 有上下文时先列已有知识点再列题目() {
        String text = KnowledgeExtractionService.formatQuestions(QUESTIONS, CONTEXT);

        assertThat(text).contains("[E1] TCP 四次挥手的过程是怎样的？\n[E2] TCP 三次握手的过程是怎样的？");
        assertThat(text.indexOf("[E1]")).isLessThan(text.indexOf("[1] 说说"));
    }
}
