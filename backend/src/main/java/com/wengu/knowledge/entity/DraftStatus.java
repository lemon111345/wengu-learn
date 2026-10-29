package com.wengu.knowledge.entity;

/** 草稿状态，按名称存进 extraction_draft.status。异步抽取的 RUNNING 由 #16 添加，确认后的状态由 #17 添加。 */
public enum DraftStatus {
    /** 抽取成功，等待用户确认 */
    READY,
    /** 重试后仍然失败，或模型调用出错 */
    FAILED
}
