package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

import java.util.List;

/**
 * 评分页显示的 AI 建议（ui.md 5.4）。
 *
 * @param rating         建议的评分，评分按钮下面标“AI 建议”，不预先选中
 * @param analysis       判断依据
 * @param weakPoints     没掌握的内容，“下次复习会重点问这里”
 * @param outOfScopeGaps 超出要点范围的缺口，候选知识点的来源之一（#31）
 */
public record RatingSuggestionView(Rating rating, String analysis, List<String> weakPoints,
                                   List<String> outOfScopeGaps) {
}
