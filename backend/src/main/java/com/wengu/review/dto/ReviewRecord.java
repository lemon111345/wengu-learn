package com.wengu.review.dto;

import com.wengu.review.fsrs.Rating;

import java.time.Instant;

/**
 * 一次评分，给其他模块看的那部分：每日计划用它显示“今天已完成”。
 *
 * @param kpId        知识点 id
 * @param rating      用户确认的评分
 * @param firstReview 是否为第一次学习（新学），否则是复习
 * @param reviewedAt  评分时间
 */
public record ReviewRecord(long kpId, Rating rating, boolean firstReview, Instant reviewedAt) {
}
