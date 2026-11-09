package com.wengu.user.dto;

import java.time.ZoneId;

/**
 * 用户设置。plan 通过 {@link com.wengu.user.service.UserSettingsService} 读取，用来算“今天”（决策 002）。
 *
 * @param timezone      IANA 时区，如 Asia/Shanghai
 * @param dayStartHour  一天从几点开始算，0～23
 * @param dailyNewLimit 每天学几个知识点，含复习（决策 016 的修订）
 * @param teacherName   老师的称呼，只改变显示名称和模型的自称（决策 042）
 */
public record SettingsView(String timezone, int dayStartHour, int dailyNewLimit, String teacherName) {

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
