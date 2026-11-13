package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

import java.time.LocalDate;

/**
 * 答法速查的一条（#112）：一个学过的知识点和它最近一次学习的面试参考答法。
 *
 * @param sessionId       最近一次已结束的学习会话，“看完整对话”打开它
 * @param referenceAnswer 从这次收尾中切出的参考答法；收尾里没有找到时为 null（决策 047）
 * @param rating          这次学习的评分；学完还没评分时为 null
 * @param studiedDate     这次学习结束的学习日
 * @param dueToday        知识点今天要复习、还没复习：前端默认折叠，免得复习前先看到答案
 */
public record AnswerSummary(long kpId, String kpTitle, long sessionId, String referenceAnswer, Rating rating,
                            LocalDate studiedDate, boolean dueToday) {
}
