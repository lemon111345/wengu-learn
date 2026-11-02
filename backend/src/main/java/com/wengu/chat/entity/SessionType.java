package com.wengu.chat.entity;

/** 会话类型（决策 018）。目前只有 STUDY，另外两种由接着问、从一个问题开始的 issue 使用。 */
public enum SessionType {
    /** 学习一个知识点：摸底 → 讲解 → … → 完整作答 → 收尾，之后评分 */
    STUDY,
    /** 接着问：评分后自由提问，不评分 */
    FOLLOW_UP,
    /** 从一个问题开始：不关联知识点（#54） */
    QUESTION
}
