package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.BatchStatus;

import java.util.List;

/**
 * 草稿中的一批。重试过的批次只返回最新的一次。
 *
 * @param no          第几批，从 1 开始
 * @param questionIds 这一批的题号，对应 source 中的题号
 */
public record BatchView(int no, BatchStatus status, List<Integer> questionIds) {
}
