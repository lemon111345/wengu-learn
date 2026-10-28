package com.wengu.chat.experiment;

import com.wengu.chat.experiment.RatingSuggestion.Rating;
import com.wengu.llm.dto.ChatTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话实验中的纯函数：拼装发给模型的消息、判断对话是否结束、生成结果文件的内容。不调用模型，便于单元测试。
 */
final class ChatExperimentSupport {

    /** 学习对话 prompt 约定的结束标记，模型收尾时单独输出一行。 */
    static final String END_MARKER = "【学习结束】";

    /** 学生回答到倒数第二次时附在消息末尾，prompt 中约定看到它就停止引导，让学生完整回答原题。 */
    static final String RESTATE_NOTE = "（只剩最后一轮：请停止讲解和追问，让学生完整回答原题。）";

    /** 学生回答达到上限时附在消息末尾，prompt 中约定看到它就直接收尾。 */
    static final String MAX_TURNS_NOTE = "（已达到最大轮数，请直接收尾。）";

    /** CSV 开头的 UTF-8 BOM，Excel 双击打开不会出现中文乱码。 */
    static final String BOM = "﻿";

    // NN-transcript.md 中的标题和标记，写入（transcript）和读回（parseTranscript）共用
    private static final String TEACHER = "【老师】";
    private static final String STUDENT = "【学生】";
    private static final String DIALOGUE_HEADING = "## 对话";
    private static final String SELF_HEADING = "## 自评";
    private static final String AI_HEADING = "## AI 评分建议";
    private static final String RATING_PREFIX = "- 评分：";

    static final List<String> CSV_HEADER = List.of(
            "知识点", "类型", "作者学习前的自述", "学生回答次数", "模型主动结束",
            "rating_ai", "AI 的判断依据", "还没掌握的点", "自评", "差距（AI − 自评）", "学懂了吗", "追问有效吗", "备注");

    private ChatExperimentSupport() {
    }

    /** 对话的第一条用户消息：知识点信息。system prompt 保持不变，知识点相关的内容都放在这里。 */
    static String kickoff(ChatKnowledgePoint kp) {
        String questions = kp.sourceQuestions().stream()
                .map(q -> "- " + q)
                .collect(Collectors.joining("\n"));
        return """
                知识点标题：%s
                类型：%s
                要点（写给你的参考，不要照着念）：%s
                来源面试题：
                %s

                请开始。""".formatted(kp.title(), typeLabel(kp), kp.summary(), questions);
    }

    /**
     * 按学生已回答的次数，在这次回答后面附上轮数提示：倒数第二次要求完整作答，最后一次要求收尾。
     *
     * @param answers 包括这一次在内，学生已回答的次数
     */
    static String withTurnNote(String answer, int answers, int maxAnswers) {
        if (answers >= maxAnswers) {
            return answer + "\n\n" + MAX_TURNS_NOTE;
        }
        if (answers == maxAnswers - 1) {
            return answer + "\n\n" + RESTATE_NOTE;
        }
        return answer;
    }

    /** 模型可能把标记写在同一行的末尾，所以只检查是否出现，不要求单独成行。 */
    static boolean containsEndMarker(String reply) {
        return reply != null && reply.contains(END_MARKER);
    }

    /**
     * 评分建议的输入：知识点和对话记录。
     *
     * @param dialogue 第一条（知识点信息）之后的对话
     */
    static String ratingInput(ChatKnowledgePoint kp, List<ChatTurn> dialogue) {
        return """
                知识点：%s
                类型：%s
                要点：%s

                对话记录：

                %s""".formatted(kp.title(), typeLabel(kp), kp.summary(), formatDialogue(dialogue));
    }

    /** 一个知识点的完整记录，写入 NN-transcript.md。suggestion 为 null 表示评分建议调用失败。 */
    static String transcript(ChatKnowledgePoint kp, List<ChatTurn> dialogue, SelfAssessment self,
                             RatingSuggestion suggestion, String ratingError) {
        List<String> parts = new ArrayList<>();
        parts.add("# " + kp.title());
        parts.add("- 类型：" + typeLabel(kp) + "\n- 学习前的自述：" + nullToEmpty(kp.note()));
        parts.add(DIALOGUE_HEADING + "\n\n" + formatDialogue(dialogue));
        parts.add(SELF_HEADING + "\n\n" + RATING_PREFIX + self.rating().display()
                + "\n- 学懂了吗：" + self.understood()
                + "\n- 追问有效吗：" + self.followUpEffective()
                + "\n- 备注：" + self.note());
        parts.add(suggestion == null
                ? AI_HEADING + "\n\n调用失败：" + ratingError
                : AI_HEADING + "\n\n" + RATING_PREFIX + suggestion.rating().display()
                + "\n- 判断依据：" + suggestion.analysis()
                + "\n- 还没掌握的点：" + weakPoints(suggestion, "；"));
        return String.join("\n\n", parts) + "\n";
    }

    /**
     * 从 NN-transcript.md 读回的内容，用于离线重评。
     *
     * @param aiRating 当时的 AI 评分建议；评分建议调用失败时为 null
     */
    record ParsedTranscript(String title, List<ChatTurn> dialogue, Rating selfRating, Rating aiRating) {
    }

    /**
     * transcript() 的逆操作：按二级标题切出对话、自评、AI 评分建议三部分，再把对话按【老师】【学生】拆回一条条消息。
     * 二级标题从后往前找，即使对话内容里出现同样的文字也不会切错。
     */
    static ParsedTranscript parseTranscript(String markdown) {
        String title = markdown.lines().findFirst().orElseThrow().substring("# ".length());
        int dialogueStart = markdown.indexOf("\n" + DIALOGUE_HEADING + "\n") + DIALOGUE_HEADING.length() + 2;
        int selfStart = markdown.lastIndexOf("\n" + SELF_HEADING + "\n");
        int aiStart = markdown.lastIndexOf("\n" + AI_HEADING + "\n");
        return new ParsedTranscript(
                title,
                parseDialogue(markdown.substring(dialogueStart, selfStart)),
                ratingIn(markdown.substring(selfStart, aiStart)),
                ratingIn(markdown.substring(aiStart)));
    }

    /** formatDialogue() 的逆操作。 */
    private static List<ChatTurn> parseDialogue(String text) {
        List<ChatTurn> turns = new ArrayList<>();
        ChatTurn.Role role = null;
        StringBuilder content = new StringBuilder();
        for (String line : text.lines().toList()) {
            ChatTurn.Role next = line.equals(TEACHER) ? ChatTurn.Role.ASSISTANT
                    : line.equals(STUDENT) ? ChatTurn.Role.USER : null;
            if (next == null) {
                content.append(line).append('\n');
                continue;
            }
            if (role != null) {
                turns.add(new ChatTurn(role, content.toString().strip()));
            }
            role = next;
            content.setLength(0);
        }
        if (role != null) {
            turns.add(new ChatTurn(role, content.toString().strip()));
        }
        return turns;
    }

    /** 找出“- 评分：GOOD（良好）”这一行并取出评分；没有这一行（调用失败）时返回 null。 */
    private static Rating ratingIn(String section) {
        return section.lines()
                .filter(line -> line.startsWith(RATING_PREFIX))
                .findFirst()
                .map(line -> Rating.valueOf(line.substring(RATING_PREFIX.length(), line.indexOf('（'))))
                .orElse(null);
    }

    /** ratings.csv 中的一行（不含行尾）。suggestion 为 null 时 AI 相关的列留空。 */
    static String csvRow(ChatKnowledgePoint kp, int answers, boolean endedByModel, SelfAssessment self,
                         RatingSuggestion suggestion) {
        String gap = suggestion == null ? "" : String.valueOf(suggestion.rating().number - self.rating().number);
        return csvLine(List.of(
                kp.title(),
                typeLabel(kp),
                nullToEmpty(kp.note()),
                String.valueOf(answers),
                endedByModel ? "是" : "否",
                suggestion == null ? "" : suggestion.rating().display(),
                suggestion == null ? "" : nullToEmpty(suggestion.analysis()),
                suggestion == null ? "" : weakPoints(suggestion, "\n"),
                self.rating().display(),
                gap,
                self.understood(),
                self.followUpEffective(),
                self.note()));
    }

    /** 每个字段都加双引号，字段内的双引号写成两个，换行和逗号因此可以原样保留。 */
    static String csvLine(List<String> fields) {
        return fields.stream()
                .map(f -> "\"" + f.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    private static String formatDialogue(List<ChatTurn> dialogue) {
        return dialogue.stream()
                .map(t -> (t.role() == ChatTurn.Role.ASSISTANT ? TEACHER : STUDENT) + "\n" + t.content().strip())
                .collect(Collectors.joining("\n\n"));
    }

    private static String weakPoints(RatingSuggestion suggestion, String delimiter) {
        return suggestion.weakPoints() == null ? "" : String.join(delimiter, suggestion.weakPoints());
    }

    private static String typeLabel(ChatKnowledgePoint kp) {
        return kp.type().name() + "（" + kp.type().label() + "）";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
