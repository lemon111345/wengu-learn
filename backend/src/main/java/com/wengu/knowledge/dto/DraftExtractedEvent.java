package com.wengu.knowledge.dto;

/**
 * 一份草稿排队的批次都执行完了（不论成败）。由抽取后台发布，发布时结果已经写进库。
 * 学习缺口的草稿据此自动确认（#31，决策 053）。
 *
 * @param draftId 执行完的草稿
 */
public record DraftExtractedEvent(long draftId) {
}
