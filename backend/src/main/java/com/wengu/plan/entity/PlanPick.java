package com.wengu.plan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.Instant;
import java.time.LocalDate;

/** 用户自己加入今天的知识点，对应 plan_pick 表（V19，决策 052）。只在 studyDate 那个学习日有效。 */
public class PlanPick {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long kpId;
    /** 加入的是哪个学习日 */
    private LocalDate studyDate;
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

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public LocalDate getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(LocalDate studyDate) {
        this.studyDate = studyDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
