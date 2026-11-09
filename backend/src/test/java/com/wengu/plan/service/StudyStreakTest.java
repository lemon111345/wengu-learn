package com.wengu.plan.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 连续学习天数的推算规则（决策 020）。纯计算，不启动 Spring。 */
class StudyStreakTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private static LocalDate daysAgo(int days) {
        return TODAY.minusDays(days);
    }

    @Test
    void 没有完成记录时为0() {
        assertThat(StudyStreak.count(TODAY, Set.of(), day -> false)).isZero();
    }

    @Test
    void 有完成记录的日子各算一天() {
        assertThat(StudyStreak.count(TODAY, Set.of(daysAgo(1), daysAgo(2), daysAgo(3)), day -> true)).isEqualTo(3);
    }

    @Test
    void 没有记录但漏了到期复习时打断() {
        Set<LocalDate> completed = Set.of(daysAgo(1), daysAgo(3), daysAgo(4));

        assertThat(StudyStreak.count(TODAY, completed, day -> true)).isEqualTo(1);
    }

    @Test
    void 没有记录也没有漏掉复习时不计入也不打断() {
        Set<LocalDate> completed = Set.of(daysAgo(1), daysAgo(3), daysAgo(4));

        assertThat(StudyStreak.count(TODAY, completed, day -> false)).isEqualTo(3);
    }

    @Test
    void 今天有完成记录时加上今天() {
        assertThat(StudyStreak.count(TODAY, Set.of(TODAY, daysAgo(1)), day -> true)).isEqualTo(2);
    }

    @Test
    void 今天还没完成时不判断今天_算到昨天为止() {
        List<LocalDate> asked = new ArrayList<>();

        int streak = StudyStreak.count(TODAY, Set.of(daysAgo(1)), day -> {
            asked.add(day);
            return true;
        });

        assertThat(streak).isEqualTo(1);
        assertThat(asked).isEmpty();
    }

    @Test
    void 昨天漏了复习时今天的完成重新从1开始() {
        assertThat(StudyStreak.count(TODAY, Set.of(TODAY, daysAgo(2), daysAgo(3)), day -> true)).isEqualTo(1);
    }

    @Test
    void 只查缺记录的日子_不走到最早的完成记录之前_遇到打断就停止() {
        List<LocalDate> asked = new ArrayList<>();
        Set<LocalDate> completed = Set.of(daysAgo(1), daysAgo(3), daysAgo(6));

        int streak = StudyStreak.count(TODAY, completed, day -> {
            asked.add(day);
            return day.equals(daysAgo(4));
        });

        assertThat(streak).isEqualTo(2);
        assertThat(asked).containsExactly(daysAgo(2), daysAgo(4));

        asked.clear();
        assertThat(StudyStreak.count(TODAY, completed, day -> {
            asked.add(day);
            return false;
        })).isEqualTo(3);
        assertThat(asked).containsExactly(daysAgo(2), daysAgo(4), daysAgo(5));
    }
}
