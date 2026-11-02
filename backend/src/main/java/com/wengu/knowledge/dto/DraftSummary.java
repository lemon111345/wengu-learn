package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.DraftStatus;

import java.time.Instant;

/**
 * 草稿列表中的一行，用于今天页的“等你确认”、导入页的“最近导入”，以及关掉页面后找回草稿。
 * 条目等详情点进去再用 GET /api/drafts/{id} 查。
 *
 * @param sourceName 来源材料的名称，材料查不到时为 null
 * @param status     和 {@link DraftView#status()} 的算法相同
 * @param itemCount  成功批次中的条目数，和详情中 items 的个数一致；确认后也不变（含取消勾选的）
 */
public record DraftSummary(long id, Long sourceId, String sourceName, DraftStatus status, int itemCount,
                           Instant createdAt) {
}
