package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.CodeFile;
import com.wengu.knowledge.dto.CodeMaterial;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.review.fsrs.Rating;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChatPromptTest {

    private static final String SUMMARY = "1. 先校验再保存。\n- 先校验内容\n- 校验通过才保存";

    private static StudyMaterial material(String details) {
        CodeMaterial code = new CodeMaterial(details, List.of("当时为什么这样选？"), List.of(),
                List.of(new CodeFile("chat/A.java", "class A {}\n"), new CodeFile("chat/B.java", "class B {}")));
        return new StudyMaterial(1, "发一条消息时依次发生了什么？", KnowledgePointType.EXPERIENCE, SUMMARY, List.of(), code);
    }

    private static Message message(MessageRole role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void 第一条消息依次是知识点信息_细节_代码证明不了的问题和源码_最后才是请开始() {
        String kickoff = CodeChatPrompt.kickoff(material(" 1. 内容不超过 5000 字。 "), null);

        assertThat(kickoff).contains("知识点标题：发一条消息时依次发生了什么？", SUMMARY,
                "\n细节（漏了不影响掌握）：1. 内容不超过 5000 字。\n",
                "代码证明不了的问题：\n- 当时为什么这样选？",
                "相关源码：\n\n===== 文件：chat/A.java =====\nclass A {}\n\n===== 文件：chat/B.java =====\nclass B {}");
        assertThat(kickoff.indexOf("细节")).isLessThan(kickoff.indexOf("相关源码"));
        assertThat(kickoff).endsWith("请开始。");
        assertThat(kickoff.indexOf("请开始。")).isEqualTo(kickoff.lastIndexOf("请开始。"));
        // 页面显示前置卡之前，不告诉老师学生读过前置卡
        assertThat(kickoff).doesNotContain("前置卡", "这是复习");
    }

    @Test
    void 复习时带上以前的学习情况_没有细节时不写细节() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, Rating.HARD, List.of("问过的问题？"),
                List.of("没掌握的点"));

        String kickoff = CodeChatPrompt.kickoff(material(null), review);

        assertThat(kickoff).contains("这是复习", "问过的问题？", "没掌握的点", "相关源码").doesNotContain("细节");
        assertThat(kickoff.indexOf("这是复习")).isLessThan(kickoff.indexOf("相关源码"));
        // 源码的复习按要点顺序走，没掌握的点讲到时才问；不能用普通学习对话的“开场优先围绕其中一点”
        assertThat(kickoff).contains(CodeChatPrompt.WEAK_POINTS_HEADING + "\n- 没掌握的点").doesNotContain("开场优先");
        assertThat(kickoff).endsWith("请开始。");
        assertThat(kickoff.indexOf("请开始。")).isEqualTo(kickoff.lastIndexOf("请开始。"));
    }

    @Test
    void 有细节的复习_细节在复习信息前面() {
        LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, Rating.GOOD, List.of(), List.of());

        String kickoff = CodeChatPrompt.kickoff(material("1. 细节"), review);

        assertThat(kickoff.indexOf("细节（漏了不影响掌握）")).isLessThan(kickoff.indexOf("这是复习"));
    }

    @Test
    void 历史第一条是源码版的第一条消息_之后是存库的消息() {
        List<ChatTurn> history = CodeChatPrompt.history(material(null), null,
                List.of(message(MessageRole.ASSISTANT, "这里会发生什么？"), message(MessageRole.USER, "先校验")));

        assertThat(history).hasSize(3);
        assertThat(history.getFirst().content()).contains("===== 文件：chat/A.java =====");
        assertThat(history.get(2).content()).isEqualTo("先校验");
    }

    @Test
    void 评分输入写明学习还是复习_核心要点和细节分开_带上源码和对话() {
        List<Message> dialogue = List.of(message(MessageRole.ASSISTANT, "问题？"), message(MessageRole.USER, "回答"));

        String study = CodeChatPrompt.ratingInput(material("1. 细节"), false, dialogue);

        assertThat(study).startsWith("知识点：发一条消息时依次发生了什么？\n这次对话：第一次学习\n核心要点：" + SUMMARY + "\n细节：1. 细节\n")
                .contains("===== 文件：chat/A.java =====", "对话记录：\n\n【老师】\n问题？\n\n【学生】\n回答");
        assertThat(CodeChatPrompt.ratingInput(material(null), true, dialogue))
                .contains("这次对话：复习\n要点：").doesNotContain("细节");
    }

    @Test
    void prompt文件能读到() {
        assertThat(CodeChatPrompt.system()).contains("这一点要答出", "逐点回忆").doesNotContain("3～5 次");
        assertThat(CodeChatPrompt.ratingSystem()).isNotBlank();
    }
}
