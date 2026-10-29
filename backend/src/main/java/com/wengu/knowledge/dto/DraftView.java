package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.DraftStatus;

import java.util.List;

/**
 * 返回给前端的草稿。失败原因只存在库里和日志中，不返回：异常信息可能带有服务商返回的内部细节。
 *
 * @param sourceId 来源材料
 * @param status   由各批的状态推算。RUNNING 时前端轮询；FAILED 时提示“抽取失败，可以重试”
 * @param batches  按批号排列
 * @param items    已经成功的批次中的知识点，按批号、批内顺序排列，order 在整份草稿内从 1 连续编号
 * @param covered  已经成功的批次中，被已有知识点或其他批次条目覆盖的题（决策 014）
 */
public record DraftView(long id, Long sourceId, DraftStatus status, List<BatchView> batches,
                        List<DraftItemView> items, List<CoveredView> covered) {
}
