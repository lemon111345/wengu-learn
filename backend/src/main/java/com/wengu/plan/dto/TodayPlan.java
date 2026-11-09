package com.wengu.plan.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 今天的计划：先复习，后新学（技术方案“每日计划的计算逻辑”）。
 * 进度 = 已完成 ÷（已完成 + 待复习 + 待新学）。
 *
 * @param date            学习日，按用户时区和 day_start_hour 算出；凌晨分界前打开时仍是前一天
 * @param dailyNewLimit   每天学几个，含复习（#85 改了含义，接口约定不重命名字段，决策 016 的修订）
 * @param newLearnedToday 今天已经学过的新知识数，已从新知识名额中扣除
 * @param reviews         到期复习，按学习顺序排列，不含今天已完成的；全部列出，可能超过每天学几个
 * @param newItems        新知识，按学习顺序排列，只补足每天学几个剩下的名额
 * @param completed       今天已完成的，按完成时间排列；暂停的也在内
 * @param upcomingReviews 接下来 7 个学习日（从明天起）每天要复习的数量；今天和已经过期的在 reviews 中
 * @param knowledgePointCount 知识点总数，暂停的和不参与调度的也算；为 0 时今天页显示空状态。
 *                            不能只看上面几个列表是否为空：知识点都在很多天后到期、或者只有不参与调度的类型时，列表也是空的
 * @param studiedMinutesToday 今天的有效学习时长（分钟，不足一分钟的舍去），来自今天开始的学习会话（决策 025）
 * @param daysToFinish    假设之后的名额都用来新学，至少还要几个学习日学完全部待学的新知识（含今天）；
 *                        每天学几个为 0 或没有待学的新知识时为 null。#85 起界面不再使用，接口约定不删除字段，所以保留
 * @param currentStreak   连续学习天数（决策 020）；今天还没完成时算到昨天为止
 * @param studiedMinutesThisWeek 本周的有效学习时长（分钟，不足一分钟的舍去），从周一的学习日开始
 */
public record TodayPlan(LocalDate date, int dailyNewLimit, int newLearnedToday, List<PlanItem> reviews,
                        List<PlanItem> newItems, List<CompletedItem> completed, List<UpcomingDay> upcomingReviews,
                        int knowledgePointCount, int studiedMinutesToday, Integer daysToFinish,
                        int currentStreak, int studiedMinutesThisWeek) {
}
