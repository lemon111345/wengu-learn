package com.wengu.chat.service;

import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
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
    void 读得到v3的prompt且约定了两个标记() {
        String system = LearningChatPrompt.system();

        assertThat(system).contains(Marker.RESTATE.text(), Marker.END.text(), "学生选择跳过讲解", "只剩最后一轮");
    }
}
