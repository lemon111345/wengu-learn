package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.knowledge.dto.KnowledgePointType;

import java.time.Instant;

/**
 * 知识点修改前的快照，对应 kp_revision 表（V11，决策 029）。每次确实有改动的编辑写一行，三个字段都存修改前的值。
 * AI 的原文在草稿条目中，按时间排起来就是“AI 原文 → 第 1 次修改前 → … → 现在”，#9 用它对比 AI 和用户的写法。
 */
public class KpRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long kpId;
    private KnowledgePointType type;
    private String title;
    private String summary;
    /** 修改的时间 */
    private Instant createdAt;

    /** 知识点当前的内容，作为修改前的快照 */
    public static KpRevision before(KnowledgePoint kp, Instant now) {
        KpRevision revision = new KpRevision();
        revision.setUserId(kp.getUserId());
        revision.setKpId(kp.getId());
        revision.setType(kp.getType());
        revision.setTitle(kp.getTitle());
        revision.setSummary(kp.getSummary());
        revision.setCreatedAt(now);
        return revision;
    }

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

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
