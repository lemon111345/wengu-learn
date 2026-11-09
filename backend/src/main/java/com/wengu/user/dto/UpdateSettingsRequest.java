package com.wengu.user.dto;

/**
 * 修改设置，只改传了的字段，为 null 的不变（PATCH）。页面上只露出 dailyNewLimit（#23）。
 *
 * @param timezone      IANA 时区
 * @param dayStartHour  0～23
 * @param dailyNewLimit 每天学几个，含复习；0～50；0 表示只复习不学新的
 * @param teacherName   老师的称呼，最多 10 个字；空白时恢复成“老师”（决策 042）
 */
public record UpdateSettingsRequest(String timezone, Integer dayStartHour, Integer dailyNewLimit, String teacherName) {

    /** 不改称呼时用，省得每处都写一个 null。接口的 JSON 走上面的完整构造方法 */
    public UpdateSettingsRequest(String timezone, Integer dayStartHour, Integer dailyNewLimit) {
        this(timezone, dayStartHour, dailyNewLimit, null);
    }
}
