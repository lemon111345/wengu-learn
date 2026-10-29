package com.wengu.plan.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class StudyDayTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static StudyDay at(String utc, ZoneId zone, int dayStartHour) {
        return StudyDay.of(Instant.parse(utc), zone, dayStartHour);
    }

    @Test
    void 分界时刻前一分钟属于前一天() {
        // 北京时间 10 月 30 日 03:59
        StudyDay day = at("2026-10-29T19:59:00Z", SHANGHAI, 4);

        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(day.start()).isEqualTo(Instant.parse("2026-10-28T20:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-10-29T20:00:00Z"));
    }

    @Test
    void 分界时刻起属于新的一天() {
        // 北京时间 10 月 30 日 04:00
        StudyDay day = at("2026-10-29T20:00:00Z", SHANGHAI, 4);

        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(day.start()).isEqualTo(Instant.parse("2026-10-29T20:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-10-30T20:00:00Z"));
    }

    @Test
    void 用户时区与UTC不在同一天() {
        // UTC 10 月 28 日 22:00，北京时间已是 10 月 29 日 06:00
        assertThat(at("2026-10-28T22:00:00Z", SHANGHAI, 4).date()).isEqualTo(LocalDate.of(2026, 10, 29));
        // UTC 10 月 29 日 02:00，纽约还是 10 月 28 日 22:00
        assertThat(at("2026-10-29T02:00:00Z", NEW_YORK, 4).date()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void 分界为零点时就是日历日() {
        StudyDay day = at("2026-10-29T16:00:00Z", SHANGHAI, 0);

        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(day.start()).isEqualTo(Instant.parse("2026-10-29T16:00:00Z"));
    }

    @Test
    void 夏令时开始那天的分界时刻不存在时往后挪() {
        // 纽约 2026-03-08 02:00 跳到 03:00；分界设为 2 点时，这一天从 03:00（UTC 07:00）开始，只有 23 小时
        StudyDay before = at("2026-03-08T06:59:00Z", NEW_YORK, 2);
        StudyDay after = at("2026-03-08T07:00:00Z", NEW_YORK, 2);

        assertThat(before.date()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(after.date()).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(after.start()).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        assertThat(after.end()).isEqualTo(Instant.parse("2026-03-09T06:00:00Z"));
        assertThat(before.end()).isEqualTo(after.start());
    }

    @Test
    void 夏令时结束那天有25小时() {
        // 纽约 2026-11-01 02:00 回拨到 01:00，01:00～02:00 出现两次；分界设为 1 点，正好落在重复的那一小时
        StudyDay day = at("2026-11-01T12:00:00Z", NEW_YORK, 1);

        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 11, 1));
        // 01:00 出现两次，取前一个（EDT，UTC 05:00）
        assertThat(day.start()).isEqualTo(Instant.parse("2026-11-01T05:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
    }
}
