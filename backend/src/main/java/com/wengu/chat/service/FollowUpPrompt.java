package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * 接着问的 prompt 和发给模型的消息（决策 008 第 4 条、决策 025）。除了读取 prompt 文件，都是纯函数。
 * <p>
 * 接着问没有老师的开场白，学生先开口：第一次提问时，在问题前面拼上知识点信息和刚才的整段学习对话。
 * 这段上下文和学习对话的第一条消息一样不存库，每次现拼。
 */
final class FollowUpPrompt {

    static final String VERSION = "v1";

    static final double TEMPERATURE = 0.7;

    /** 提问的上限，较高、不显示给用户（决策 018）。第 30 次回答之后会话结束 */
    static final int MAX_QUESTIONS = 30;

    /** 第几次提问时提醒学生可以先回去 */
    static final int REMIND_AT = 26;

    static final String REMIND_NOTE = "（接近上限：请照常回答，然后加上约定的那句提醒。）";

    private static final String PROMPT_PATH = "prompts/follow-up-chat-" + VERSION + ".md";

    private FollowUpPrompt() {
    }

    static String system() {
        return LearningChatPrompt.load(PROMPT_PATH);
    }

    /** 第 questions 次提问要附的提示：接近上限时提醒一次，其他时候没有。 */
    static String turnNote(int questions) {
        return questions == REMIND_AT ? REMIND_NOTE : null;
    }

    /** 拼在第一个问题前面的上下文：知识点信息和刚才的学习对话（已去掉标记）。 */
    static String context(StudyMaterial material, List<Message> studyMessages) {
        String dialogue = LearningChatPrompt.transcript(studyMessages);
        return """
                知识点标题：%s
                类型：%s（%s）
                要点：%s

                刚才的学习对话：

                %s

                ---

                学生的问题：""".formatted(material.title(), material.type().name(), material.type().label(),
                material.summary(), dialogue);
    }

    /** 发给模型的完整历史：第一个问题前面带上上下文，之后照常追加。 */
    static List<ChatTurn> history(StudyMaterial material, List<Message> studyMessages, List<Message> messages) {
        List<ChatTurn> turns = new ArrayList<>();
        for (Message m : messages) {
            ChatTurn turn = LearningChatPrompt.turn(m);
            turns.add(turns.isEmpty() && m.getRole() == MessageRole.USER
                    ? ChatTurn.user(context(material, studyMessages) + "\n" + turn.content())
                    : turn);
        }
        return turns;
    }
}
