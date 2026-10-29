package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.DraftStatus;

import java.util.List;

/**
 * 返回给前端的草稿。失败原因只存在库里和日志中，不返回：异常信息可能带有服务商返回的内部细节。
 *
 * @param sourceId 来源材料
 * @param status   FAILED 时 items 为空，前端提示“抽取失败，可以重试”
 * @param items    按学习顺序排列的知识点
 */
public record DraftView(long id, Long sourceId, DraftStatus status, List<DraftItemView> items) {
}
