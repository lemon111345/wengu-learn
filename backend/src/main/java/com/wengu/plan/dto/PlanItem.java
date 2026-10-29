package com.wengu.plan.dto;

import com.wengu.knowledge.dto.KnowledgePointType;

/**
 * 计划中的一个知识点。
 *
 * @param kpId  知识点 id，开始学习时用它创建会话（#19）
 * @param title 写成一个问题的标题
 * @param type  知识点类型
 */
public record PlanItem(long kpId, String title, KnowledgePointType type) {
}
