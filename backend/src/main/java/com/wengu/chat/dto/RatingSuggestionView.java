package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

import java.util.List;

/**
 * 评分页显示的 AI 建议（ui.md 5.4）。
 *
 * @param rating         建议的评分，评分按钮下面标“AI 建议”，不预先选中
 * @param analysis       判断依据
 * @param weakPoints     没掌握的内容，“下次复习会重点问这里”
 * @param outOfScopeGaps 超出要点范围的缺口，评分页按题目显示，用户勾选的加入知识库（#31，决策 053）
 * @param gapPicks       勾选加入知识库的缺口是 outOfScopeGaps 的第几条，从小到大；还没处理时为 null
 * @param gapDraftId     由勾选的缺口生成的草稿，前端用它查看整理进度和结果；还没处理时为 null
 */
public record RatingSuggestionView(Rating rating, String analysis, List<String> weakPoints,
                                   List<String> outOfScopeGaps, List<Integer> gapPicks, Long gapDraftId) {
}
