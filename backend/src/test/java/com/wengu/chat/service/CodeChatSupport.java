package com.wengu.chat.service;

import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.entity.Marker;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.review.fsrs.Rating;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 结合源码学习的实验（issue #89）中的纯函数：拼第一条消息、评分输入和结果文件。不调用模型，有单元测试。
 * <p>
 * 第一条消息直接用正式的 {@link LearningChatPrompt#kickoff}，只在后面加上源码，和现有学习对话的差别一眼能看出来。
 */
final class CodeChatSupport {

    /** 抽取实验 result.json 中的一个代码依据。 */
    record Evidence(String file, String symbol) {
    }

    /** 抽取实验 result.json 中的一个前置概念（抽取 v2 起有）。 */
    record Prerequisite(String concept, String explanation) {
    }

    /**
     * 抽取实验 result.json 中的一个知识点。prerequisites、summaryDetails 是抽取 v2 加的，读 v1 的结果时为 null；
     * v2 的 summary 只有核心要点。
     */
    record CodeKp(String angle, List<Evidence> evidence, int order, List<Prerequisite> prerequisites, String summary,
                  String summaryDetails, String title, List<String> unverifiable) {

        boolean hasPrerequisites() {
            return prerequisites != null && !prerequisites.isEmpty();
        }

        boolean hasDetails() {
            return summaryDetails != null && !summaryDetails.isBlank();
        }
    }

    record Extraction(List<CodeKp> knowledgePoints) {
    }

    /**
     * 一次学习或复习的完整记录，写成 JSON，隔天复习和离线重评都读它。
     *
     * @param mode          study 或 review
     * @param kickoffChars  第一条消息的字符数，每轮都要发送，用来估算费用
     * @param dialogue      第一条消息之后的对话，学生消息不含轮数提示
     * @param codeDependent 作者判断：追问是否离不开这份代码
     * @param teacherWrong  作者判断：老师有没有说错代码
     * @param suggestion    AI 的评分建议，调用失败时为 null
     * @param prerequisitesHelpful 作者判断：前置卡有没有帮助。没有显示前置卡时（复习、v1 的知识点）为 null
     * @param mainLineClear 作者判断：主线清不清楚。第三轮起才问，之前的记录为 null
     */
    record SessionRecord(int order, String title, String angle, String mode, String chatPromptVersion,
                         String ratingPromptVersion, int answers, boolean endedByModel, long durationSeconds,
                         int kickoffChars, List<ChatTurn> dialogue, Rating selfRating, String understood,
                         String codeDependent, String teacherWrong, String note, RatingSuggestionResult suggestion,
                         String ratingError, String prerequisitesHelpful, String mainLineClear) {
    }

    private CodeChatSupport() {
    }

    /** 知识点的依据涉及哪些文件，去重后保持原顺序。 */
    static List<String> evidenceFiles(CodeKp kp) {
        return kp.evidence().stream().map(Evidence::file).distinct().toList();
    }

    /**
     * 第一条消息：正式的知识点信息和复习信息，再加上细节、代码证明不了的问题和源码。
     * 第一次学习时，学生开始前读过前置卡，也告诉老师读了哪几张；复习时不显示前置卡，也就不提。
     */
    static String kickoff(CodeKp kp, Map<String, String> files, LearningChatPrompt.Review review) {
        StudyMaterial material = new StudyMaterial(0, kp.title(), KnowledgePointType.EXPERIENCE, kp.summary(), List.of());
        String base = LearningChatPrompt.kickoff(material, review);
        String head = base.substring(0, base.lastIndexOf("请开始。")).stripTrailing();
        String details = kp.hasDetails() ? "\n细节（漏了不影响掌握）：" + kp.summaryDetails() : "";
        String cards = review == null && kp.hasPrerequisites()
                ? "\n\n学生开始前读过这几张前置卡：\n" + prerequisiteCards(kp) : "";
        return head + details + cards + "\n\n" + codeContext(kp, files) + "\n\n请开始。";
    }

    /**
     * 评分建议的输入：知识点、这次是学习还是复习（EASY 的标准不同）、要点、源码和对话记录。
     * 有细节时把核心要点和细节分开写；v1 的知识点没有细节，写法和以前一样，第一轮的记录重评时输入不变。
     */
    static String ratingInput(CodeKp kp, Map<String, String> files, boolean isReview, List<ChatTurn> dialogue) {
        String points = kp.hasDetails() ? "\n核心要点：" + kp.summary() + "\n细节：" + kp.summaryDetails()
                : "\n要点：" + kp.summary();
        return "知识点：" + kp.title() + "\n这次对话：" + (isReview ? "复习" : "第一次学习") + points
                + "\n\n" + codeContext(kp, files) + "\n\n对话记录：\n\n" + formatDialogue(dialogue);
    }

    /** 前置卡：学生读的和告诉老师的是同一份文字。 */
    static String prerequisiteCards(CodeKp kp) {
        return kp.prerequisites().stream()
                .map(p -> "【" + p.concept() + "】\n" + p.explanation().strip())
                .collect(Collectors.joining("\n\n"));
    }

    private static String codeContext(CodeKp kp, Map<String, String> files) {
        String unverifiable = kp.unverifiable() == null || kp.unverifiable().isEmpty() ? "- （无）"
                : kp.unverifiable().stream().map(q -> "- " + q).collect(Collectors.joining("\n"));
        String code = files.entrySet().stream()
                .map(e -> "===== 文件：" + e.getKey() + " =====\n" + e.getValue().stripTrailing())
                .collect(Collectors.joining("\n\n"));
        return "代码证明不了的问题：\n" + unverifiable + "\n\n相关源码：\n\n" + code;
    }

    /** 根据上一次学习的记录，算出复习时要带上的学习情况，规则和正式的复习一致（决策 035）。 */
    static LearningChatPrompt.Review reviewOf(SessionRecord study) {
        List<String> teacherReplies = study.dialogue().stream()
                .filter(t -> t.role() == ChatTurn.Role.ASSISTANT)
                .map(ChatTurn::content)
                .filter(c -> !c.contains(Marker.RESTATE.text()) && !c.contains(Marker.END.text()))
                .toList();
        List<String> weakPoints = study.suggestion() == null || study.suggestion().weakPoints() == null
                ? List.of() : study.suggestion().weakPoints();
        return new LearningChatPrompt.Review(1, study.selfRating(), LearningChatPrompt.askedQuestions(teacherReplies),
                weakPoints);
    }

    static String formatDialogue(List<ChatTurn> dialogue) {
        return dialogue.stream()
                .map(t -> (t.role() == ChatTurn.Role.ASSISTANT ? "【老师】" : "【学生】") + "\n" + t.content().strip())
                .collect(Collectors.joining("\n\n"));
    }

    /** 给人读的完整记录。 */
    static String transcript(SessionRecord r) {
        RatingSuggestionResult s = r.suggestion();
        String ai = s == null ? "调用失败：" + r.ratingError()
                : "- 评分：" + s.rating() + "\n- 判断依据：" + s.analysis()
                + "\n- 还没掌握的点：" + join(s.weakPoints()) + "\n- 超出范围的缺口：" + join(s.outOfScopeGaps());
        return "# " + r.title() + "\n\n- 模式：" + r.mode() + "\n- 角度：" + r.angle() + "\n- 学生回答次数：" + r.answers()
                + "\n\n## 对话\n\n" + formatDialogue(r.dialogue())
                + "\n\n## 自评\n\n- 评分：" + r.selfRating() + "\n- 学懂了吗：" + r.understood()
                + "\n- 追问离不开这份代码吗：" + r.codeDependent() + "\n- 老师说错代码了吗：" + r.teacherWrong()
                + (r.prerequisitesHelpful() == null ? "" : "\n- 前置卡有帮助吗：" + r.prerequisitesHelpful())
                + (r.mainLineClear() == null ? "" : "\n- 主线清楚吗：" + r.mainLineClear())
                + "\n- 备注：" + r.note() + "\n\n## AI 评分建议\n\n" + ai + "\n";
    }

    private static String join(List<String> items) {
        return items == null ? "" : String.join("；", items);
    }
}
