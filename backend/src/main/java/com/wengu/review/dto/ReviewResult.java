package com.wengu.review.dto;

import com.wengu.review.fsrs.Rating;

import java.time.Instant;

/**
 * 一次评分的结果。
 *
 * @param rating      用户确认的评分
 * @param due         下次复习的时间
 * @param firstReview 是否为这个知识点的第一次学习
 */
public record ReviewResult(Rating rating, Instant due, boolean firstReview) {
}
