package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 学习对话的 prompt 和发给模型的消息（决策 008、025）。除了读取 prompt 文件，都是纯函数，便于单元测试。
 * <p>
 * system prompt 保持不变，知识点相关的内容都放在第一条用户消息里。第一条消息不存库，每次由学习材料现拼；
 * 附在学生消息后面的提示（轮数、跳过讲解）存在 message.model_note 上，重建历史时原样带上。
 */
final class LearningChatPrompt {

    static final String VERSION = "v3";

    /** 模型的随机性：讲解要有些变化，和 #14 实验一致 */
    static final double TEMPERATURE = 0.7;

    /**
     * 学生回答的上限：第 7 次回答后要求完整作答，第 8 次后要求收尾（决策 008 第 3 条）。
     * 这是防止老师追问停不下来的保险，不限制学生；学生想继续用“接着问”。
     */
    static final int MAX_ANSWERS = 8;

    static final String RESTATE_NOTE = "（只剩最后一轮：请停止讲解和追问，让学生完整回答原题。）";
    static final String MAX_ANSWERS_NOTE = "（已达到最大轮数，请直接收尾。）";
    static final String SKIP_NOTE = "（学生选择跳过讲解：请直接进入完整作答这一步。）";

    /** 学生点“这个我会，直接完整作答”时，对话中显示的学生消息 */
    static final String SKIP_MESSAGE = "这个我会，直接完整作答。";

    private static final String PROMPT_PATH = "prompts/learning-chat-" + VERSION + ".md";

    private LearningChatPrompt() {
    }

    static String system() {
        return load(PROMPT_PATH);
    }

    /** 读取 classpath 下的 prompt 文件，接着问的 prompt 也用它。 */
    static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败：" + path, e);
        }
    }

    /** 对话的第一条用户消息：知识点信息，格式和 #14 实验一致。 */
    static String kickoff(StudyMaterial material) {
        String questions = material.sourceQuestions().isEmpty() ? "- （无）"
                : material.sourceQuestions().stream().map(q -> "- " + q).collect(Collectors.joining("\n"));
        return """
                知识点标题：%s
                类型：%s（%s）
                要点（写给你的参考，不要照着念）：%s
                来源面试题：
                %s

                请开始。""".formatted(material.title(), material.type().name(), material.type().label(),
                material.summary(), questions);
    }

    /**
     * 第 answers 次回答要附的轮数提示：倒数第二次要求完整作答，最后一次要求收尾，其他时候没有。
     *
     * @param answers 包括这一次在内，学生已回答的次数
     */
    static String turnNote(int answers) {
        if (answers >= MAX_ANSWERS) {
            return MAX_ANSWERS_NOTE;
        }
        return answers == MAX_ANSWERS - 1 ? RESTATE_NOTE : null;
    }

    /**
     * 发给模型的完整历史：第一条是知识点信息，之后是存库的消息。
     * 学生消息带上当时附的提示；老师的回复把识别时去掉的标记补回去，和模型当时的输出一致。
     */
    static List<ChatTurn> history(StudyMaterial material, List<Message> messages) {
        List<ChatTurn> turns = new ArrayList<>();
        turns.add(ChatTurn.user(kickoff(material)));
        messages.forEach(m -> turns.add(turn(m)));
        return turns;
    }

    /** 对话记录的文字版：【老师】【学生】分段，标记已经去掉。接着问的上下文、评分建议的输入都用它，格式和 #14 实验一致 */
    static String transcript(List<Message> messages) {
        return messages.stream()
                .map(m -> (m.getRole() == MessageRole.ASSISTANT ? "【老师】" : "【学生】") + "\n" + m.getContent().strip())
                .collect(Collectors.joining("\n\n"));
    }

    /** 一条存库的消息还原成模型当时看到的样子：学生消息带上附加的提示，老师的回复补回标记。接着问也用它。 */
    static ChatTurn turn(Message m) {
        if (m.getRole() == MessageRole.USER) {
            return ChatTurn.user(m.getModelNote() == null ? m.getContent() : m.getContent() + "\n\n" + m.getModelNote());
        }
        return ChatTurn.assistant(m.getMarker() == null ? m.getContent() : m.getContent() + "\n" + m.getMarker().text());
    }
}
