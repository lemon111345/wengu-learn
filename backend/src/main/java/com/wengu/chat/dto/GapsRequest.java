package com.wengu.chat.dto;

import java.util.List;

/**
 * 把勾选的学习缺口加入知识库（#31，决策 053）。
 *
 * @param indices 勾选的是评分建议 outOfScopeGaps 的第几条，从 0 起，至少一条、不能重复
 */
public record GapsRequest(List<Integer> indices) {
}
