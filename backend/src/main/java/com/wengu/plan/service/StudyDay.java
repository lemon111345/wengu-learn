package com.wengu.plan.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 用户的“今天”（学习日）：从用户时区的 day_start_hour 开始，到第二天的 day_start_hour 为止。
 * 纯计算，不依赖 Spring，时间一律用 UTC 的 Instant 表示（CLAUDE.md）。
 * <p>
 * 例如时区 Asia/Shanghai、day_start_hour 为 4：北京时间 10 月 30 日 03:59 仍属于 10 月 29 日这个学习日，
 * 04:00 起才是 10 月 30 日。熬夜学到凌晨，学的还算前一天。
 *
 * @param date  学习日的日期
 * @param start 开始时间（含）
 * @param end   结束时间（不含），即下一个学习日的开始
 */
public record StudyDay(LocalDate date, Instant start, Instant end) {

    public static StudyDay of(Instant now, ZoneId zone, int dayStartHour) {
        LocalDate date = now.atZone(zone).toLocalDate();
        Instant start = startOf(date, zone, dayStartHour);
        // 比较时刻而不是比较小时：分界时刻落在夏令时跳过的那一小时里时，startOf 会往后挪，比较小时会算错
        if (now.isBefore(start)) {
            date = date.minusDays(1);
            start = startOf(date, zone, dayStartHour);
        }
        return new StudyDay(date, start, startOf(date.plusDays(1), zone, dayStartHour));
    }

    /**
     * 某个日期的 day_start_hour 对应的时刻。这个时刻因夏令时不存在时，ZonedDateTime 会往后挪到存在的时刻；
     * 有两个时（夏令时结束时重复的一小时），取前一个。所以这样的学习日可能是 23 或 25 小时。
     */
    private static Instant startOf(LocalDate date, ZoneId zone, int dayStartHour) {
        return ZonedDateTime.of(date, LocalTime.of(dayStartHour, 0), zone).toInstant();
    }
}
