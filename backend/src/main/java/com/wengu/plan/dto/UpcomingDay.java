package com.wengu.plan.dto;

import java.time.LocalDate;

/**
 * 接下来某一个学习日要复习的数量，今天页右侧的柱子（ui.md 5.2）。
 *
 * @param date  学习日；星期几由前端从这个日期算，不用浏览器的“今天”
 * @param count 这一天到期的复习数，不含暂停的
 */
public record UpcomingDay(LocalDate date, int count) {
}
