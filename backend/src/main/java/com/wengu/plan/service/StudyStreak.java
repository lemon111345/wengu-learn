package com.wengu.plan.service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/** 连续学习天数（决策 020）。纯计算：日期都是学习日，数据由调用方传入。 */
final class StudyStreak {

    private StudyStreak() {
    }

    /**
     * 从昨天往回逐日判断，走到最早一条完成记录为止，再早的日子不可能再加天数。
     * 今天还没结束，没有完成记录时不判断，否则每天打开时都会被算成打断。
     *
     * @param today        今天的学习日
     * @param completed    有“当天完成”记录的学习日
     * @param missedReview 某个没有完成记录的学习日，是否漏了到期复习。只对这样的日子调用，遇到第一个漏了的就停止
     */
    static int count(LocalDate today, Set<LocalDate> completed, Predicate<LocalDate> missedReview) {
        if (completed.isEmpty()) {
            return 0;
        }
        LocalDate earliest = Collections.min(completed);
        int streak = completed.contains(today) ? 1 : 0;
        for (LocalDate day = today.minusDays(1); !day.isBefore(earliest); day = day.minusDays(1)) {
            if (completed.contains(day)) {
                streak++;
            } else if (missedReview.test(day)) {
                break;
            }
            // 没有记录、也没有漏掉复习：这天没有任务，不计入也不打断
        }
        return streak;
    }
}
