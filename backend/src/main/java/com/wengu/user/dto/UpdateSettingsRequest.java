package com.wengu.user.dto;

/**
 * 修改设置，只改传了的字段，为 null 的不变（PATCH）。页面上只露出 dailyNewLimit（#23）。
 *
 * @param timezone      IANA 时区
 * @param dayStartHour  0～23
 * @param dailyNewLimit 0～50；0 表示只复习不学新的
 */
public record UpdateSettingsRequest(String timezone, Integer dayStartHour, Integer dailyNewLimit) {
}
