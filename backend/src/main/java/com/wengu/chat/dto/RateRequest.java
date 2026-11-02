package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

/**
 * 用户确认的评分。
 *
 * @param rating AGAIN / HARD / GOOD / EASY
 */
public record RateRequest(Rating rating) {
}
