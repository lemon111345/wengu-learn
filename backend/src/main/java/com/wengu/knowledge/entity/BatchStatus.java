package com.wengu.knowledge.entity;

/** 抽取批次的状态，按名称存进 extraction_batch.status。 */
public enum BatchStatus {
    /** 已进入队列，还没开始调用模型 */
    PENDING,
    /** 正在调用模型 */
    RUNNING,
    /** 抽取成功 */
    READY,
    /** 重试后仍然失败、模型调用出错，或任务被重启中断 */
    FAILED
}
