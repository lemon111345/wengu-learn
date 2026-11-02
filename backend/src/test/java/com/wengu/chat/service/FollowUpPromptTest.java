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

/** 接着问发给模型的消息（决策 025）。纯计算，不需要 Spring。 */
class FollowUpPromptTest {

    private static final StudyMaterial MATERIAL = new StudyMaterial(1, "HashMap 的扩容机制是怎样的？",
            KnowledgePointType.MECHANISM, "负载因子 0.75；容量翻倍", List.of());

    private static Message message(MessageRole role, String content, Marker marker, String note) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        m.setMarker(marker);
        m.setModelNote(note);
        return m;
    }

    private static final List<Message> STUDY = List.of(
            message(MessageRole.ASSISTANT, "你知道负载因子吗？", null, null),
            message(MessageRole.USER, "是 0.75", null, null),
            message(MessageRole.ASSISTANT, "参考答法……", Marker.END, null));

    @Test
    void 第一个问题前面带上知识点信息和去掉标记的学习对话() {
        List<ChatTurn> history = FollowUpPrompt.history(MATERIAL, STUDY, List.of(
                message(MessageRole.USER, "为什么是 0.75？", null, null),
                message(MessageRole.ASSISTANT, "这是时间和空间的折中……", null, null),
                message(MessageRole.USER, "那 Java 8 的红黑树呢？", null, FollowUpPrompt.REMIND_NOTE)));

        assertThat(history).hasSize(3);
        String first = history.getFirst().content();
        assertThat(first).startsWith("知识点标题：HashMap 的扩容机制是怎样的？\n类型：MECHANISM（原理机制）")
                .contains("【老师】\n你知道负载因子吗？\n\n【学生】\n是 0.75\n\n【老师】\n参考答法……")
                .doesNotContain(Marker.END.text())
                .endsWith("学生的问题：\n为什么是 0.75？");
        assertThat(history.get(1)).isEqualTo(ChatTurn.assistant("这是时间和空间的折中……"));
        assertThat(history.get(2)).isEqualTo(ChatTurn.user("那 Java 8 的红黑树呢？\n\n" + FollowUpPrompt.REMIND_NOTE));
    }

    @Test
    void 只在第26次提问时提醒() {
        assertThat(FollowUpPrompt.turnNote(25)).isNull();
        assertThat(FollowUpPrompt.turnNote(26)).isEqualTo(FollowUpPrompt.REMIND_NOTE);
        assertThat(FollowUpPrompt.turnNote(27)).isNull();
    }

    @Test
    void 读得到接着问的prompt() {
        assertThat(FollowUpPrompt.system()).contains("只回答学生的问题", "接近上限",
                "这次聊得比较多了，可以先回去，把聊到的新知识加入复习，明天接着学。");
    }
}
