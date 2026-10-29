package com.wengu.knowledge.entity;

import java.util.Collection;

/**
 * 草稿状态，由各批的状态推算，不存库（决策 013）。确认后的状态由 #17 添加。
 */
public enum DraftStatus {
    /** 还有批次在排队或执行中 */
    RUNNING,
    /** 所有批次都抽取成功，等待用户确认 */
    READY,
    /** 没有批次在执行，但至少有一批失败；成功的批次的条目照常保留 */
    FAILED;

    /** 根据每一批（重试时取最新的一行）的状态推算。 */
    public static DraftStatus of(Collection<BatchStatus> batches) {
        if (batches.stream().anyMatch(s -> s == BatchStatus.PENDING || s == BatchStatus.RUNNING)) {
            return RUNNING;
        }
        return batches.contains(BatchStatus.FAILED) ? FAILED : READY;
    }
}
