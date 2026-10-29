package com.wengu.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.State;

import java.time.Instant;

/**
 * 一个知识点的调度状态，对应 card_state 表（V1）。字段和 {@link Card} 一一对应。
 * <p>
 * 主键是 kp_id，由调用方给定，不自增：一个知识点只有一行调度状态。
 */
public class CardState {

    @TableId(type = IdType.INPUT)
    private Long kpId;
    /** 冗余，用于按用户查到期（决策 009） */
    private Long userId;
    private State state;
    private Integer step;
    private Double stability;
    private Double difficulty;
    private Instant due;
    /** 为空表示新知识 */
    private Instant lastReview;

    /** 把 FSRS 的卡片转成一行调度状态。 */
    public static CardState of(long kpId, long userId, Card card) {
        CardState cs = new CardState();
        cs.setKpId(kpId);
        cs.setUserId(userId);
        cs.setState(card.state());
        cs.setStep(card.step());
        cs.setStability(card.stability());
        cs.setDifficulty(card.difficulty());
        cs.setDue(card.due());
        cs.setLastReview(card.lastReview());
        return cs;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public Double getStability() {
        return stability;
    }

    public void setStability(Double stability) {
        this.stability = stability;
    }

    public Double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Double difficulty) {
        this.difficulty = difficulty;
    }

    public Instant getDue() {
        return due;
    }

    public void setDue(Instant due) {
        this.due = due;
    }

    public Instant getLastReview() {
        return lastReview;
    }

    public void setLastReview(Instant lastReview) {
        this.lastReview = lastReview;
    }
}
