package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.knowledge.dto.CodeMaterial;
import com.wengu.knowledge.dto.CodePrerequisite;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 源码知识点（#90）的学习对话和评分建议：对话用 v5（#89 实验的 v4 加上复习的固定结构），评分用实验的 v2（决策 048），
 * 第一条消息和评分输入的格式也和实验一致，实验的结论才能沿用。
 * <p>
 * 第一条消息在普通知识点的信息（{@link LearningChatPrompt#kickoff}）后面，加上细节、前置卡（第一次学习时）、复习信息、代码证明不了的问题和源码。
 * 回答上限、完整作答和收尾的标记、轮数提示都和普通学习对话相同。
 */
final class CodeChatPrompt {

    /** 存进 study_session.prompt_version，和普通学习对话的版本区分开。v5 起复习也按要点顺序逐点走（决策 048 第 5 条） */
    static final String VERSION = "code-v5";

    /** 存进 rating_suggestion.prompt_version */
    static final String RATING_VERSION = "code-v2";

    private static final String PROMPT_PATH = "prompts/code-learning-chat-v5.md";

    /** 复习按要点顺序走，没掌握的点讲到时才重点问；不能沿用普通学习对话的“开场优先围绕其中一点” */
    static final String WEAK_POINTS_HEADING = "上次没掌握的（讲到它所在的那一点时重点问，不要提前问）：";

    private static final String RATING_PROMPT_PATH = "prompts/code-rating-suggestion-v2.md";

    private CodeChatPrompt() {
    }

    static String system() {
        return LearningChatPrompt.load(PROMPT_PATH);
    }

    static String ratingSystem() {
        return LearningChatPrompt.load(RATING_PROMPT_PATH);
    }

    /**
     * 第一条消息：普通知识点的信息、细节、复习信息、代码证明不了的问题和源码，最后是“请开始。”。
     * 复习信息里“上次没掌握的”用源码自己的说法：讲到那一点时重点问，不在开场先问（v5）。
     * 第一次学习且有前置卡时，在细节后面写上学生读过哪几张：学习页在老师开口前显示前置卡，读完才开始（ui.md 5.3）。
     * 复习时页面不显示前置卡，也就不写。写法和 #89 实验相同。
     *
     * @param review 复习时以前的学习情况；第一次学习为 null
     */
    static String kickoff(StudyMaterial material, LearningChatPrompt.Review review) {
        CodeMaterial code = material.code();
        String base = LearningChatPrompt.kickoff(material, null);
        String head = base.substring(0, base.lastIndexOf("请开始。")).stripTrailing();
        String details = code.hasDetails() ? "\n细节（漏了不影响掌握）：" + code.details().strip() : "";
        String cards = review == null && !code.prerequisites().isEmpty()
                ? "\n\n学生开始前读过这几张前置卡：\n" + cards(code.prerequisites()) : "";
        String reviewInfo = review == null ? "" : "\n\n" + LearningChatPrompt.reviewSection(review, WEAK_POINTS_HEADING);
        return head + details + cards + reviewInfo + "\n\n" + codeContext(code) + "\n\n请开始。";
    }

    /** 前置卡：学生在页面上读的和告诉老师的是同一份文字 */
    private static String cards(List<CodePrerequisite> prerequisites) {
        return prerequisites.stream()
                .map(p -> "【" + p.concept() + "】\n" + p.explanation().strip())
                .collect(Collectors.joining("\n\n"));
    }

    /** 发给模型的完整历史：第一条是 {@link #kickoff}，之后是存库的消息，还原方式和普通学习对话相同 */
    static List<ChatTurn> history(StudyMaterial material, LearningChatPrompt.Review review, List<Message> messages) {
        List<ChatTurn> turns = new ArrayList<>();
        turns.add(ChatTurn.user(kickoff(material, review)));
        messages.forEach(m -> turns.add(LearningChatPrompt.turn(m)));
        return turns;
    }

    /**
     * 评分建议的输入：这次是第一次学习还是复习（EASY 的标准不同）、核心要点和细节分开写、源码和对话记录。
     * 要点里每条下面的得分点就是评分时逐个核对的内容（决策 050）。
     */
    static String ratingInput(StudyMaterial material, boolean isReview, List<Message> messages) {
        CodeMaterial code = material.code();
        String points = code.hasDetails()
                ? "\n核心要点：" + material.summary() + "\n细节：" + code.details().strip()
                : "\n要点：" + material.summary();
        return "知识点：" + material.title() + "\n这次对话：" + (isReview ? "复习" : "第一次学习") + points
                + "\n\n" + codeContext(code) + "\n\n对话记录：\n\n" + LearningChatPrompt.transcript(messages);
    }

    private static String codeContext(CodeMaterial code) {
        String unverifiable = code.unverifiable().isEmpty() ? "- （无）"
                : code.unverifiable().stream().map(q -> "- " + q).collect(Collectors.joining("\n"));
        String files = code.files().stream()
                .map(f -> "===== 文件：" + f.path() + " =====\n" + f.content().stripTrailing())
                .collect(Collectors.joining("\n\n"));
        return "代码证明不了的问题：\n" + unverifiable + "\n\n相关源码：\n\n" + files;
    }
}
