package com.wengu.review.dto;

import java.time.Instant;

/**
 * 一张复习过的卡和它的到期时间，每日计划用它统计“接下来 7 天要复习的”。
 *
 * @param kpId 知识点 id
 * @param due  下次到期时间
 */
public record DueCard(long kpId, Instant due) {
}
