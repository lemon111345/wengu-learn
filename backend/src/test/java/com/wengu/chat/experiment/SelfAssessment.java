package com.wengu.chat.experiment;

/**
 * 作者学完一个知识点后的自评，在看到 AI 评分建议之前填写，避免被 AI 的结论带偏。
 *
 * @param rating           自评的四档评分
 * @param understood       讲解是否让人学懂了
 * @param followUpEffective 追问是否有效
 * @param note             备注
 */
record SelfAssessment(RatingSuggestion.Rating rating, String understood, String followUpEffective, String note) {
}
