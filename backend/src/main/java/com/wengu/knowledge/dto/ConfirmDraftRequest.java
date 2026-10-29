package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 确认草稿。
 *
 * @param rejectedItemIds 用户取消勾选的条目 id；为 null 或空时全部接受
 */
public record ConfirmDraftRequest(List<Long> rejectedItemIds) {
}
