package com.wengu.review.dto;

import com.wengu.review.fsrs.Rating;

import java.time.Instant;

/**
 * 一次评分，给其他模块看的那部分：每日计划用它显示“今天已完成”，知识库详情用它显示学习记录（#50）。
 *
 * @param kpId        知识点 id
 * @param rating      用户确认的评分
 * @param ratingAi    AI 建议的评分；建议还没生成时为空
 * @param firstReview 是否为第一次学习（新学），否则是复习
 * @param reviewedAt  评分时间
 * @param sessionId   这次学习的会话，回顾时打开它（#82）；没有时为 null
 */
public record ReviewRecord(long kpId, Rating rating, Rating ratingAi, boolean firstReview, Instant reviewedAt,
                           Long sessionId) {
}
