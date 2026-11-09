package com.wengu.knowledge.dto;

import com.wengu.review.fsrs.Rating;

import java.time.LocalDate;

/**
 * 知识点详情中的一条学习记录，显示为“10月29日 新学，良好（AI 建议：良好）”（ui.md 5.9）。
 *
 * @param date        学习日
 * @param firstReview 是否为新学，否则是复习
 * @param rating      用户确认的评分
 * @param ratingAi    AI 建议的评分；建议还没生成时为空
 * @param mergedFrom  来自被合并的知识点时为它的标题，自己的为 null（决策 029：学习记录留在被合并的知识点上）
 * @param sessionId   那次学习的会话，点开回顾（#82）；没有时为 null
 */
public record StudyRecord(LocalDate date, boolean firstReview, Rating rating, Rating ratingAi, String mergedFrom,
                          Long sessionId) {
}
