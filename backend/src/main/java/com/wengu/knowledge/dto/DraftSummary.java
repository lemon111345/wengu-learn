package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.DraftStatus;

import java.time.Instant;

/**
 * 草稿列表中的一行，用于关掉页面后找回草稿。条目等详情点进去再用 GET /api/drafts/{id} 查。
 *
 * @param sourceName 来源材料的名称，材料查不到时为 null
 * @param status     和 {@link DraftView#status()} 的算法相同
 */
public record DraftSummary(long id, Long sourceId, String sourceName, DraftStatus status, Instant createdAt) {
}
