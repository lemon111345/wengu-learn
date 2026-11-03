package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.review.fsrs.Rating;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 学习对话的 prompt 和发给模型的消息（决策 008、025）。除了读取 prompt 文件，都是纯函数，便于单元测试。
 * <p>
 * system prompt 保持不变，知识点相关的内容都放在第一条用户消息里。第一条消息不存库，每次由学习材料现拼；
 * 附在学生消息后面的提示（轮数、跳过讲解）存在 message.model_note 上，重建历史时原样带上。
 */
final class LearningChatPrompt {

    /** v4：新增“复习”流程，复习时第一条消息带上以前的学习情况（#21，决策 035） */
    static final String VERSION = "v4";

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

    /** 复习时最多带上多少个以前问过的问题：太多会让第一条消息过长，最近的最有参考价值 */
    static final int MAX_ASKED_QUESTIONS = 15;

    /**
     * 复习时附在第一条消息里的学习情况（#21）。第一次学习时没有，为 null。
     *
     * @param timesRated     以前评过几次分（第一次学习也算一次）
     * @param lastRating     最近一次的评分
     * @param askedQuestions 以前的学习会话中老师问过的问题，按时间先后，最多 {@link #MAX_ASKED_QUESTIONS} 个
     * @param weakPoints     最近一次评分建议中“没掌握的”，没有时为空
     */
    record Review(int timesRated, Rating lastRating, List<String> askedQuestions, List<String> weakPoints) {
    }

    /** 对话的第一条用户消息：知识点信息，格式和 #14 实验一致。 */
    static String kickoff(StudyMaterial material) {
        return kickoff(material, null);
    }

    /** 复习时在知识点信息后面加上以前的学习情况，见 prompt 的“复习”一节 */
    static String kickoff(StudyMaterial material, Review review) {
        String questions = material.sourceQuestions().isEmpty() ? "- （无）"
                : bullets(material.sourceQuestions());
        String info = """
                知识点标题：%s
                类型：%s（%s）
                要点（写给你的参考，不要照着念）：%s
                来源面试题：
                %s""".formatted(material.title(), material.type().name(), material.type().label(),
                material.summary(), questions);
        return review == null ? info + "\n\n请开始。" : info + "\n\n" + reviewSection(review) + "\n\n请开始。";
    }

    private static String reviewSection(Review review) {
        StringBuilder text = new StringBuilder("这是复习：这个知识点以前学过 %d 次，上次的评分是“%s”。"
                .formatted(review.timesRated(), RATING_LABELS.get(review.lastRating())));
        if (!review.weakPoints().isEmpty()) {
            text.append("\n上次没掌握的（开场优先围绕其中一点提问）：\n").append(bullets(review.weakPoints()));
        }
        if (!review.askedQuestions().isEmpty()) {
            text.append("\n以前问过的问题（换一个角度，不要重复，也不要只是换个说法）：\n")
                    .append(bullets(review.askedQuestions()));
        }
        return text.toString();
    }

    /** 和界面上的说法一致（PRD“忘了 / 困难 / 良好 / 简单”） */
    private static final Map<Rating, String> RATING_LABELS = Map.of(
            Rating.AGAIN, "忘了", Rating.HARD, "困难", Rating.GOOD, "良好", Rating.EASY, "简单");

    private static String bullets(List<String> items) {
        return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    /**
     * 从老师的回复中取出问过的问题：prompt 要求“每次只问一个问题，放在回复的最后”，所以取每条回复中最后一个问句。
     * 同样的问题只留一个；超过 {@link #MAX_ASKED_QUESTIONS} 个时留最近的。
     *
     * @param teacherReplies 老师的回复，按时间先后；调用方去掉要求完整作答和收尾的回复，它们每次都一样，不算“问过”
     */
    static List<String> askedQuestions(List<String> teacherReplies) {
        List<String> questions = teacherReplies.stream()
                .map(LearningChatPrompt::lastQuestion)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return questions.subList(Math.max(0, questions.size() - MAX_ASKED_QUESTIONS), questions.size());
    }

    /** 句子的结束符：问句从上一个结束符之后开始 */
    private static final String SENTENCE_ENDS = "。！？!?\n";

    /**
     * 一条回复中最后一个问题，去掉 Markdown 的加粗和列表符号；没有问句时为 null。
     * 老师常连着问两句（“……会发生什么？为什么要这样设计？”），同一行中紧挨着的问句一起取，只取最后一句会丢掉前面的场景
     */
    static String lastQuestion(String reply) {
        int end = Math.max(reply.lastIndexOf('？'), reply.lastIndexOf('?'));
        if (end < 0) {
            return null;
        }
        int start = previousSentenceEnd(reply, end);
        while (start >= 0 && (reply.charAt(start) == '？' || reply.charAt(start) == '?')) {
            start = previousSentenceEnd(reply, start);
        }
        String question = reply.substring(start + 1, end + 1)
                .replace("**", "")
                .replaceFirst("^\\s*(?:[-*>]|\\d+[.、])\\s*", "")
                .strip();
        return question.length() < 4 ? null : question;
    }

    /** pos 之前最近的句子结束符的位置，没有时为 -1 */
    private static int previousSentenceEnd(String text, int pos) {
        int i = pos - 1;
        while (i >= 0 && SENTENCE_ENDS.indexOf(text.charAt(i)) < 0) {
            i--;
        }
        return i;
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
        return history(material, null, messages);
    }

    /** 同上，复习时第一条消息带上以前的学习情况 */
    static List<ChatTurn> history(StudyMaterial material, Review review, List<Message> messages) {
        List<ChatTurn> turns = new ArrayList<>();
        turns.add(ChatTurn.user(kickoff(material, review)));
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
