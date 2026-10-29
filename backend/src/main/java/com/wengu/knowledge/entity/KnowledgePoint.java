package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.knowledge.dto.KnowledgePointType;

import java.time.Instant;

/** 知识点，对应 knowledge_point 表（V1）。确认草稿时写入（#17）。 */
public class KnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private KnowledgePointType type;
    /** 写成一个问题 */
    private String title;
    private String summary;
    private KnowledgePointStatus status;
    /** 新知识按 sort_order、id 派发 */
    private Integer sortOrder;
    /** 来自哪个草稿条目（#9 对比用）；合并或手动新建的知识点为空 */
    private Long draftItemId;
    private Instant createdAt;
    private Instant updatedAt;

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

    public KnowledgePointType getType() {
        return type;
    }

    public void setType(KnowledgePointType type) {
        this.type = type;
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

    public KnowledgePointStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgePointStatus status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getDraftItemId() {
        return draftItemId;
    }

    public void setDraftItemId(Long draftItemId) {
        this.draftItemId = draftItemId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
