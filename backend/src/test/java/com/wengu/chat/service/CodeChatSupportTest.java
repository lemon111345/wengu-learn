package com.wengu.chat.service;

import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.service.CodeChatSupport.CodeKp;
import com.wengu.chat.service.CodeChatSupport.Evidence;
import com.wengu.chat.service.CodeChatSupport.SessionRecord;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.review.fsrs.Rating;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChatSupportTest {

    private static final CodeKp KP = new CodeKp("FAILURE",
            List.of(new Evidence("a/A.java", "A#x"), new Evidence("a/A.java", "A#y"), new Evidence("b/B.java", "B")),
            2, null, "要点内容", null, "连点两次会怎样？", List.of("当时为什么这样选？"));
    private static final CodeKp KP_V2 = new CodeKp("CHAIN", List.of(new Evidence("a/A.java", "A#x")), 3,
            List.of(new CodeChatSupport.Prerequisite("唯一索引", "  同一列不能有两条一样的值。  ")), "1. 核心", "1. 细节",
            "这里怎么做的？", List.of());
    private static final Map<String, String> FILES = Map.of("a/A.java", "class A {}\n");

    @Test
    void 依据的文件去重且保持顺序() {
        assertThat(CodeChatSupport.evidenceFiles(KP)).containsExactly("a/A.java", "b/B.java");
    }

    @Test
    void 第一条消息带上知识点_证明不了的问题和源码_最后才是请开始() {
        String kickoff = CodeChatSupport.kickoff(KP, FILES, null);

        assertThat(kickoff).contains("知识点标题：连点两次会怎样？", "要点内容", "- 当时为什么这样选？",
                "===== 文件：a/A.java =====\nclass A {}");
        assertThat(kickoff).endsWith("请开始。");
        assertThat(kickoff.indexOf("请开始。")).isEqualTo(kickoff.lastIndexOf("请开始。"));
        assertThat(kickoff).doesNotContain("这是复习");
    }

    @Test
    void 复习时第一条消息带上以前的学习情况() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, Rating.HARD, List.of("问过的问题？"), List.of("没掌握的点"));

        assertThat(CodeChatSupport.kickoff(KP, FILES, review)).contains("这是复习", "问过的问题？", "没掌握的点", "相关源码");
    }

    @Test
    void 复习信息不算要求完整作答和收尾的回复() {
        List<ChatTurn> dialogue = List.of(
                ChatTurn.assistant("这里会发生什么？"),
                ChatTurn.user("回答"),
                ChatTurn.assistant("请完整回答，可以吗？\n" + Marker.RESTATE.text()),
                ChatTurn.user("完整回答"),
                ChatTurn.assistant("讲得不错，对吗？\n" + Marker.END.text()));
        RatingSuggestionResult suggestion = new RatingSuggestionResult("依据", List.of(), Rating.HARD, List.of("薄弱点"));

        LearningChatPrompt.Review review = CodeChatSupport.reviewOf(record(dialogue, suggestion));

        assertThat(review.askedQuestions()).containsExactly("这里会发生什么？");
        assertThat(review.weakPoints()).containsExactly("薄弱点");
        assertThat(review.lastRating()).isEqualTo(Rating.GOOD);
    }

    @Test
    void 评分建议失败时复习没有薄弱点() {
        assertThat(CodeChatSupport.reviewOf(record(List.of(), null)).weakPoints()).isEmpty();
    }

    @Test
    void 评分输入包含学习或复习_要点_源码和对话() {
        List<ChatTurn> dialogue = List.of(ChatTurn.assistant("问题？"), ChatTurn.user("回答"));

        assertThat(CodeChatSupport.ratingInput(KP, FILES, false, dialogue))
                .contains("这次对话：第一次学习", "要点：要点内容", "class A {}", "【老师】\n问题？\n\n【学生】\n回答");
        assertThat(CodeChatSupport.ratingInput(KP, FILES, true, dialogue)).contains("这次对话：复习");
    }

    @Test
    void 没有细节时评分输入和第一轮一样_重评第一轮的记录输入不变() {
        String input = CodeChatSupport.ratingInput(KP, FILES, false, List.of());

        assertThat(input).contains("\n要点：要点内容\n").doesNotContain("核心要点", "细节");
    }

    @Test
    void 有细节时评分输入分开写核心要点和细节() {
        String input = CodeChatSupport.ratingInput(KP_V2, FILES, false, List.of());

        assertThat(input).contains("\n核心要点：1. 核心\n细节：1. 细节\n").doesNotContain("\n要点：");
    }

    @Test
    void 第一次学习时第一条消息带上细节和前置卡() {
        String kickoff = CodeChatSupport.kickoff(KP_V2, FILES, null);

        assertThat(kickoff).contains("细节（漏了不影响掌握）：1. 细节", "学生开始前读过这几张前置卡：\n【唯一索引】\n同一列不能有两条一样的值。");
        assertThat(kickoff.indexOf("前置卡")).isLessThan(kickoff.indexOf("相关源码"));
    }

    @Test
    void 复习时不提前置卡_学生这次没有读() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, Rating.HARD, List.of(), List.of());

        assertThat(CodeChatSupport.kickoff(KP_V2, FILES, review)).contains("细节（漏了不影响掌握）").doesNotContain("前置卡");
    }

    @Test
    void 第一轮的知识点没有前置卡和细节() {
        assertThat(KP.hasPrerequisites()).isFalse();
        assertThat(KP.hasDetails()).isFalse();
        assertThat(CodeChatSupport.kickoff(KP, FILES, null)).doesNotContain("前置卡", "细节");
    }

    @Test
    void 记录里有主线清不清楚时才写进记录_之前几轮的记录不多出这一行() {
        SessionRecord old = record(List.of(), null);
        SessionRecord v3 = new SessionRecord(2, "标题", "FAILURE", "study", "v3", "v2", 2, true, 10, 100, List.of(),
                Rating.GOOD, "懂了", "y", "n", "", null, "失败", null, "清楚", null);

        assertThat(CodeChatSupport.transcript(old)).doesNotContain("主线清楚吗");
        assertThat(CodeChatSupport.transcript(v3)).contains("- 主线清楚吗：清楚");
    }

    @Test
    void 认同AI评分理由的回答写在AI评分建议后面_没问过时不写() {
        RatingSuggestionResult suggestion = new RatingSuggestionResult("依据", List.of(), Rating.HARD, List.of());
        SessionRecord v4 = new SessionRecord(2, "标题", "FAILURE", "study", "v4", "v2", 2, true, 10, 100, List.of(),
                Rating.GOOD, "懂了", "y", "n", "", suggestion, null, null, "清楚", "认同");

        String transcript = CodeChatSupport.transcript(v4);

        assertThat(transcript).contains("- 认同 AI 的评分理由吗：认同");
        assertThat(transcript.indexOf("认同 AI")).isGreaterThan(transcript.indexOf("## AI 评分建议"));
        assertThat(CodeChatSupport.transcript(record(List.of(), null))).doesNotContain("认同 AI");
    }

    private static SessionRecord record(List<ChatTurn> dialogue, RatingSuggestionResult suggestion) {
        return new SessionRecord(2, "标题", "FAILURE", "study", "v1", "v1", 2, true, 10, 100, dialogue, Rating.GOOD,
                "懂了", "y", "n", "", suggestion, null, null, null, null);
    }
}
