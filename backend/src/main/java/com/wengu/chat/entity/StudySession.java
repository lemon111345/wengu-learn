package com.wengu.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.Instant;

/** 学习会话，对应 study_session 表（V1、V8、V9）。也是自动计时的来源。 */
public class StudySession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private SessionType type;
    /** 学习的知识点；从一个问题开始时为空 */
    private Long kpId;
    /** 接着问的会话指向原来的学习会话；其他类型为空 */
    private Long parentSessionId;
    private Instant startedAt;
    /** 为空表示还没结束 */
    private Instant endedAt;
    private Boolean skippedExplanation;
    /** 有效时长（秒） */
    private Integer activeSeconds;
    private Instant lastActiveAt;
    private String promptVersion;

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

    public SessionType getType() {
        return type;
    }

    public void setType(SessionType type) {
        this.type = type;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public Long getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(Long parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Boolean getSkippedExplanation() {
        return skippedExplanation;
    }

    public void setSkippedExplanation(Boolean skippedExplanation) {
        this.skippedExplanation = skippedExplanation;
    }

    public Integer getActiveSeconds() {
        return activeSeconds;
    }

    public void setActiveSeconds(Integer activeSeconds) {
        this.activeSeconds = activeSeconds;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }
}
