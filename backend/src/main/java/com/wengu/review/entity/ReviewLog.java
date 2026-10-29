package com.wengu.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;

import java.time.Instant;

/**
 * 一次评分的流水，对应 review_log 表（V1）。只增不改，供统计和以后优化 FSRS 参数。写入由 #20 添加。
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
    /** 是否为该知识点的第一次学习。新卡的 state 也是 LEARNING，不能用 stateBefore 判断（决策 003） */
    @TableField("is_first_review")
    private Boolean firstReview;
    private Instant reviewedAt;

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

    public Boolean getFirstReview() {
        return firstReview;
    }

    public void setFirstReview(Boolean firstReview) {
        this.firstReview = firstReview;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
