package com.wengu.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/** 用户设置，对应 user_settings 表（V1）。默认用户的设置由 V1 插入。 */
public class UserSettings {

    @TableId(type = IdType.INPUT)
    private Long userId;
    /** 每天新学几个知识点 */
    private Integer dailyNewLimit;
    /** IANA 时区，如 Asia/Shanghai */
    private String timezone;
    /** 一天从几点开始算，0～23 */
    private Integer dayStartHour;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getDailyNewLimit() {
        return dailyNewLimit;
    }

    public void setDailyNewLimit(Integer dailyNewLimit) {
        this.dailyNewLimit = dailyNewLimit;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getDayStartHour() {
        return dayStartHour;
    }

    public void setDayStartHour(Integer dayStartHour) {
        this.dayStartHour = dayStartHour;
    }
}
