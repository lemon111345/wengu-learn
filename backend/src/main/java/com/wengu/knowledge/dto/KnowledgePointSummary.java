package com.wengu.knowledge.dto;

/**
 * 知识点的简要信息，用于每日计划等列表。
 *
 * @param id    知识点 id
 * @param title 写成一个问题的标题
 * @param type  知识点类型，决定学习时用哪种对话方式
 */
public record KnowledgePointSummary(long id, String title, KnowledgePointType type) {
}
