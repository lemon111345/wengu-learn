package com.wengu.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.review.fsrs.Rating;

import java.time.Instant;

/** AI 评分建议，对应 rating_suggestion 表（V10）。每个学习会话一条，生成后不再改。 */
public class RatingSuggestion {

    /** 学习会话 id，由调用方给定 */
    @TableId(type = IdType.INPUT)
    private Long sessionId;
    private Long userId;
    private Rating rating;
    private String analysis;
    /** JSON 数组 */
    private String weakPoints;
    /** JSON 数组 */
    private String outOfScopeGaps;
    private String model;
    private String promptVersion;
    private Integer promptTokens;
    private Integer completionTokens;
    private Instant createdAt;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Rating getRating() {
        return rating;
    }

    public void setRating(Rating rating) {
        this.rating = rating;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public String getWeakPoints() {
        return weakPoints;
    }

    public void setWeakPoints(String weakPoints) {
        this.weakPoints = weakPoints;
    }

    public String getOutOfScopeGaps() {
        return outOfScopeGaps;
    }

    public void setOutOfScopeGaps(String outOfScopeGaps) {
        this.outOfScopeGaps = outOfScopeGaps;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
