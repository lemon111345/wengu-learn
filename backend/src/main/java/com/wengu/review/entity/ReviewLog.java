package com.wengu.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;

import java.time.Instant;

/**
 * 一次评分的流水，对应 review_log 表（V1、V10、V13）。供统计和以后优化 FSRS 参数。
 * <p>
 * 只增不删。唯一会改的是修改评分（决策 039）：更新 ratingUser、dueAfter，其他字段保持第一次评分时的值。
 */
public class ReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long kpId;
    private Long sessionId;
    /** 用户确认的评分 */
    private Rating ratingUser;
    /** AI 建议的评分；调用失败时为空 */
    private Rating ratingAi;
    /** 评分前的调度状态 */
    private State stateBefore;
    /** 评分前的快照（决策 039），和 stateBefore 合起来是一张完整的卡片；V13 之前的记录都为空 */
    private Integer stepBefore;
    private Double stabilityBefore;
    private Double difficultyBefore;
    private Instant dueBefore;
    private Instant lastReviewBefore;
    /** 是否为该知识点的第一次学习。新卡的 state 也是 LEARNING，不能用 stateBefore 判断（决策 003） */
    @TableField("is_first_review")
    private Boolean firstReview;
    /** 评分后的下次到期时间（决策 020）；V10 之前的记录为空 */
    private Instant dueAfter;
    private Instant reviewedAt;

    /** 记下评分前的卡片。 */
    public void snapshotBefore(Card card) {
        stateBefore = card.state();
        stepBefore = card.step();
        stabilityBefore = card.stability();
        difficultyBefore = card.difficulty();
        dueBefore = card.due();
        lastReviewBefore = card.lastReview();
    }

    /** 有没有快照：due 不会为空，而新卡的 stability、difficulty、lastReview 本来就是空的，不能用它们判断。 */
    public boolean hasSnapshot() {
        return dueBefore != null;
    }

    /** 还原评分前的卡片，只在 {@link #hasSnapshot} 时调用。 */
    public Card cardBefore() {
        return new Card(stateBefore, stepBefore, stabilityBefore, difficultyBefore, dueBefore, lastReviewBefore);
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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Rating getRatingUser() {
        return ratingUser;
    }

    public void setRatingUser(Rating ratingUser) {
        this.ratingUser = ratingUser;
    }

    public Rating getRatingAi() {
        return ratingAi;
    }

    public void setRatingAi(Rating ratingAi) {
        this.ratingAi = ratingAi;
    }

    public State getStateBefore() {
        return stateBefore;
    }

    public void setStateBefore(State stateBefore) {
        this.stateBefore = stateBefore;
    }

    public Integer getStepBefore() {
        return stepBefore;
    }

    public void setStepBefore(Integer stepBefore) {
        this.stepBefore = stepBefore;
    }

    public Double getStabilityBefore() {
        return stabilityBefore;
    }

    public void setStabilityBefore(Double stabilityBefore) {
        this.stabilityBefore = stabilityBefore;
    }

    public Double getDifficultyBefore() {
        return difficultyBefore;
    }

    public void setDifficultyBefore(Double difficultyBefore) {
        this.difficultyBefore = difficultyBefore;
    }

    public Instant getDueBefore() {
        return dueBefore;
    }

    public void setDueBefore(Instant dueBefore) {
        this.dueBefore = dueBefore;
    }

    public Instant getLastReviewBefore() {
        return lastReviewBefore;
    }

    public void setLastReviewBefore(Instant lastReviewBefore) {
        this.lastReviewBefore = lastReviewBefore;
    }

    public Boolean getFirstReview() {
        return firstReview;
    }

    public void setFirstReview(Boolean firstReview) {
        this.firstReview = firstReview;
    }

    public Instant getDueAfter() {
        return dueAfter;
    }

    public void setDueAfter(Instant dueAfter) {
        this.dueAfter = dueAfter;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
