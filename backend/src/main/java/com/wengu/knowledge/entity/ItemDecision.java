package com.wengu.knowledge.entity;

/** 确认草稿时对一个条目的处理，按名称存进 extraction_draft_item.decision。为空表示草稿还没确认。 */
public enum ItemDecision {
    /** 勾选：生成了知识点 */
    ACCEPTED,
    /** 用户取消勾选，没有生成知识点。记下来，以后能看出 AI 抽取了哪些用户不要的内容（#9） */
    REJECTED
}
