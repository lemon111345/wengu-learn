package com.wengu.knowledge.entity;

/** 知识点状态，按名称存进 knowledge_point.status。 */
public enum KnowledgePointStatus {
    /** 正常参与每日计划 */
    ACTIVE,
    /** 暂停：暂时不学，知识点仍然存在（#25） */
    SUSPENDED
}
