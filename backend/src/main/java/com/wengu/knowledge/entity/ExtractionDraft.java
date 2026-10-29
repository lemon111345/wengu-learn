package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.Instant;

/**
 * 一次知识点抽取，对应 extraction_draft 表。每次调用模型的信息记在批次上（{@link ExtractionBatch}），
 * 草稿的状态由批次推算（{@link DraftStatus#of}）。
 */
public class ExtractionDraft {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sourceId;
    private Instant createdAt;
    /** 确认时间，为空表示还没确认（决策 015） */
    private Instant confirmedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
