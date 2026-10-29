package com.wengu.plan.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 今天的计划：先复习，后新学（技术方案“每日计划的计算逻辑”）。
 *
 * @param date            学习日，按用户时区和 day_start_hour 算出；凌晨分界前打开时仍是前一天
 * @param dailyNewLimit   每天新学几个
 * @param newLearnedToday 今天已经学过的新知识数，已从新知识名额中扣除
 * @param reviews         到期复习，按学习顺序排列
 * @param newItems        新知识，按学习顺序排列，最多“每天新学数 − 今天已学数”个
 */
public record TodayPlan(LocalDate date, int dailyNewLimit, int newLearnedToday, List<PlanItem> reviews,
                        List<PlanItem> newItems) {
}
