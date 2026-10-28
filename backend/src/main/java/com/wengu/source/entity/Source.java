package com.wengu.source.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.Instant;

/** 导入的学习材料，对应 source 表。 */
public class Source {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private SourceType type;
    private String name;
    /** 用户粘贴的原文，包括答案 */
    private String content;
    /** 切分出的题目，{@link com.wengu.source.dto.SourceQuestion} 列表的 JSON */
    private String questions;
    private Instant createdAt;

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

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getQuestions() {
        return questions;
    }

    public void setQuestions(String questions) {
        this.questions = questions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
