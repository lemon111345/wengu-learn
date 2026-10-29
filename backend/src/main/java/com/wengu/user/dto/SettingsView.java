package com.wengu.user.dto;

import java.time.ZoneId;

/**
 * 用户设置。plan 通过 {@link com.wengu.user.service.UserSettingsService} 读取，用来算“今天”（决策 002）。
 *
 * @param timezone      IANA 时区，如 Asia/Shanghai
 * @param dayStartHour  一天从几点开始算，0～23
 * @param dailyNewLimit 每天新学几个知识点
 */
public record SettingsView(String timezone, int dayStartHour, int dailyNewLimit) {

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
