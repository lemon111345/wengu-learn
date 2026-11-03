package com.wengu.chat.service;

import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.review.fsrs.Rating;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 学习对话发给模型的消息（决策 008、025）。纯计算，不需要 Spring。 */
class LearningChatPromptTest {

    private static final StudyMaterial MATERIAL = new StudyMaterial(1, "HashMap 的扩容机制是怎样的？",
            KnowledgePointType.MECHANISM, "负载因子 0.75；容量翻倍；rehash", List.of("说说 HashMap 的原理"));

    private static Message message(MessageRole role, String content, Marker marker, String note) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        m.setMarker(marker);
        m.setModelNote(note);
        return m;
    }

    @Test
    void 第一条消息是知识点信息() {
        assertThat(LearningChatPrompt.kickoff(MATERIAL)).isEqualTo("""
                知识点标题：HashMap 的扩容机制是怎样的？
                类型：MECHANISM（原理机制）
                要点（写给你的参考，不要照着念）：负载因子 0.75；容量翻倍；rehash
                来源面试题：
                - 说说 HashMap 的原理

                请开始。""");
        StudyMaterial noSource = new StudyMaterial(1, "t", KnowledgePointType.CONCEPT, "s", List.of());
        assertThat(LearningChatPrompt.kickoff(noSource)).contains("来源面试题：\n- （无）");
    }

    @Test
    void 第7次回答要求完整作答第8次要求收尾() {
        assertThat(LearningChatPrompt.turnNote(6)).isNull();
        assertThat(LearningChatPrompt.turnNote(7)).isEqualTo(LearningChatPrompt.RESTATE_NOTE);
        assertThat(LearningChatPrompt.turnNote(8)).isEqualTo(LearningChatPrompt.MAX_ANSWERS_NOTE);
        assertThat(LearningChatPrompt.turnNote(9)).isEqualTo(LearningChatPrompt.MAX_ANSWERS_NOTE);
    }

    @Test
    void 重建历史时带上提示并补回标记() {
        List<ChatTurn> history = LearningChatPrompt.history(MATERIAL, List.of(
                message(MessageRole.ASSISTANT, "你知道负载因子吗？", null, null),
                message(MessageRole.USER, LearningChatPrompt.SKIP_MESSAGE, null, LearningChatPrompt.SKIP_NOTE),
                message(MessageRole.ASSISTANT, "请完整回答。", Marker.RESTATE, null)));

        assertThat(history).containsExactly(
                ChatTurn.user(LearningChatPrompt.kickoff(MATERIAL)),
                ChatTurn.assistant("你知道负载因子吗？"),
                ChatTurn.user("这个我会，直接完整作答。\n\n" + LearningChatPrompt.SKIP_NOTE),
                ChatTurn.assistant("请完整回答。\n【完整作答】"));
    }

    @Test
    void 读得到v4的prompt且约定了两个标记和复习流程() {
        String system = LearningChatPrompt.system();

        assertThat(system).contains(Marker.RESTATE.text(), Marker.END.text(), "学生选择跳过讲解", "只剩最后一轮",
                "这是复习", "以前问过的问题", "上次没掌握的");
    }

    @Test
    void 复习时第一条消息带上以前的学习情况() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(2, Rating.HARD,
                List.of("负载因子为什么是 0.75？", "扩容时元素怎么迁移？"), List.of("迁移时的高低位拆分"));

        assertThat(LearningChatPrompt.kickoff(MATERIAL, review)).isEqualTo("""
                知识点标题：HashMap 的扩容机制是怎样的？
                类型：MECHANISM（原理机制）
                要点（写给你的参考，不要照着念）：负载因子 0.75；容量翻倍；rehash
                来源面试题：
                - 说说 HashMap 的原理

                这是复习：这个知识点以前学过 2 次，上次的评分是“困难”。
                上次没掌握的（开场优先围绕其中一点提问）：
                - 迁移时的高低位拆分
                以前问过的问题（换一个角度，不要重复，也不要只是换个说法）：
                - 负载因子为什么是 0.75？
                - 扩容时元素怎么迁移？

                请开始。""");
    }

    @Test
    void 复习时没有薄弱点和问题就只写一句() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, Rating.GOOD, List.of(), List.of());

        assertThat(LearningChatPrompt.kickoff(MATERIAL, review))
                .endsWith("- 说说 HashMap 的原理\n\n这是复习：这个知识点以前学过 1 次，上次的评分是“良好”。\n\n请开始。");
    }

    @Test
    void 取每条回复最后一个问句并去掉格式() {
        assertThat(LearningChatPrompt.lastQuestion("好的。负载因子默认是 0.75。那么，**为什么不是 1？**")).isEqualTo("那么，为什么不是 1？");
        assertThat(LearningChatPrompt.lastQuestion("讲完了。\n\n- 如果容量不是 2 的幂会怎样？")).isEqualTo("如果容量不是 2 的幂会怎样？");
        assertThat(LearningChatPrompt.lastQuestion("你知道吗？我先讲一下。现在请想想：桶里的链表会怎样?"))
                .isEqualTo("现在请想想：桶里的链表会怎样?");
        // 连着问的两句一起取，不然只剩“为什么？”；换行之前的问句不算
        assertThat(LearningChatPrompt.lastQuestion("讲完了。检验一下：删到只剩 3 个节点会发生什么？为什么要这样设计？"))
                .isEqualTo("检验一下：删到只剩 3 个节点会发生什么？为什么要这样设计？");
        assertThat(LearningChatPrompt.lastQuestion("上一段问过你吗？\n那么扩容会怎样？")).isEqualTo("那么扩容会怎样？");
        assertThat(LearningChatPrompt.lastQuestion("这一步讲完了，我们继续。")).isNull();
        assertThat(LearningChatPrompt.lastQuestion("对吗？")).isNull();
    }

    @Test
    void 问过的问题去重并只留最近的() {
        assertThat(LearningChatPrompt.askedQuestions(List.of("先说说负载因子是什么？", "讲解。没有问题", "先说说负载因子是什么？", "为什么要翻倍？")))
                .containsExactly("先说说负载因子是什么？", "为什么要翻倍？");

        List<String> many = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> "第 " + i + " 个问题是什么？").toList();
        List<String> kept = LearningChatPrompt.askedQuestions(many);
        assertThat(kept).hasSize(LearningChatPrompt.MAX_ASKED_QUESTIONS);
        assertThat(kept.getFirst()).isEqualTo("第 6 个问题是什么？");
        assertThat(kept.getLast()).isEqualTo("第 20 个问题是什么？");
    }
}
