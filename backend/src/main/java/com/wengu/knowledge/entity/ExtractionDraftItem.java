package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.knowledge.dto.KnowledgePointType;

/** 草稿中的一个知识点，对应 extraction_draft_item 表。 */
public class ExtractionDraftItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long draftId;
    /** 来自哪一批（extraction_batch.id） */
    private Long batchId;
    /** 在这一批中的学习顺序，从 1 开始 */
    private Integer itemOrder;
    private String title;
    private String summary;
    private KnowledgePointType type;
    /** 来源题号，逗号分隔，如 "1,3,5" */
    private String sourceQuestionIds;

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

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Integer getItemOrder() {
        return itemOrder;
    }

    public void setItemOrder(Integer itemOrder) {
        this.itemOrder = itemOrder;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public KnowledgePointType getType() {
        return type;
    }

    public void setType(KnowledgePointType type) {
        this.type = type;
    }

    public String getSourceQuestionIds() {
        return sourceQuestionIds;
    }

    public void setSourceQuestionIds(String sourceQuestionIds) {
        this.sourceQuestionIds = sourceQuestionIds;
    }
}
