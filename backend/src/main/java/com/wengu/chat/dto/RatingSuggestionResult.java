package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

import java.util.List;

/**
 * 模型给出的评分建议，字段名即 JSON 中的字段名，含义见 prompts/rating-suggestion-v3.md。
 * <p>
 * Spring AI 生成的 JSON Schema 按字段名字母顺序排列，模型也按这个顺序输出：判断依据 analysis 排在 rating 前面，
 * 模型先写依据、再给结论（决策 008）。组件也按这个顺序声明。从 #14 的实验代码移到正式代码时，评分改用 FSRS 的 Rating。
 *
 * @param analysis       判断依据
 * @param outOfScopeGaps 学生还不会、但超出要点范围的内容，每条是一个可以单独学习的知识点标题；模型可能漏掉，此时为 null
 * @param rating         建议的评分
 * @param weakPoints     要点范围内学生还没掌握的内容；模型可能漏掉，此时为 null
 */
public record RatingSuggestionResult(String analysis, List<String> outOfScopeGaps, Rating rating,
                                     List<String> weakPoints) {
}
