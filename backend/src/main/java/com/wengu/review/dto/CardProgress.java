package com.wengu.review.dto;

import java.time.Instant;

/**
 * 一张卡的复习进度，给其他模块看的那部分：知识库据此判断掌握程度、显示下次复习（#50）。
 *
 * @param kpId       知识点 id
 * @param stability  FSRS 稳定性（天）；新卡为空
 * @param due        下次到期时间
 * @param lastReview 上次复习时间；为空表示新知识，还没学过
 */
public record CardProgress(long kpId, Double stability, Instant due, Instant lastReview) {
}
