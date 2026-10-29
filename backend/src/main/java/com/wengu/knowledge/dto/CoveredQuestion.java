package com.wengu.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 模型判断“这道题考的内容已经有了”：已被去重上下文中的某个已有知识点覆盖（决策 014）。
 * 一道题可以同时出现在这里和某个新知识点的来源题号里，表示只有一部分被覆盖。
 *
 * @param questionId  题号
 * @param existingIds 覆盖它的已有知识点编号，如 "E3"，对应 {@link com.wengu.knowledge.service.DedupContext} 中的编号
 * @param reason      判断理由，便于排查误判
 */
@JsonPropertyOrder({"questionId", "existingIds", "reason"})
public record CoveredQuestion(int questionId, List<String> existingIds, String reason) {
}
