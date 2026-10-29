package com.wengu.plan.service;

import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 每日计划，每次打开时实时计算，不建表：它是派生数据，事实已经在 card_state 和 review_log 里
 * （技术方案“每日计划的计算逻辑”，决策 016）。
 * <p>
 * 每个模块只读自己的表：review 给出候选 kp_id，knowledge 过滤掉暂停的并排序，user 提供设置（决策 002）。
 */
@Service
public class PlanService {

    private final UserSettingsService settingsService;
    private final ReviewService reviewService;
    private final KnowledgePointService knowledgePointService;
    private final Clock clock;

    public PlanService(UserSettingsService settingsService, ReviewService reviewService,
                       KnowledgePointService knowledgePointService, Clock clock) {
        this.settingsService = settingsService;
        this.reviewService = reviewService;
        this.knowledgePointService = knowledgePointService;
        this.clock = clock;
    }

    public TodayPlan today(long userId) {
        // 1. 根据用户时区和 day_start_hour 算出“今天”
        SettingsView settings = settingsService.get(userId);
        StudyDay day = StudyDay.of(Instant.now(clock), settings.zoneId(), settings.dayStartHour());

        // 2. 到期复习：今天结束前到期的，包括今天晚些时候才到期的
        List<PlanItem> reviews = items(knowledgePointService.activeInOrder(userId,
                reviewService.dueKpIds(userId, day.end())));

        // 3、4. 扣除今天已学的新知识，否则下午再打开时会再派一批，每天的上限就失效了
        int learned = reviewService.countFirstReviews(userId, day.start(), day.end());
        int remaining = Math.max(0, settings.dailyNewLimit() - learned);

        // 5、6. 新知识：去掉暂停的，按学习顺序取前 remaining 个
        List<PlanItem> newItems = remaining == 0 ? List.of()
                : items(knowledgePointService.activeInOrder(userId, reviewService.newKpIds(userId)))
                .stream().limit(remaining).toList();

        // 7. 先复习，后新学
        return new TodayPlan(day.date(), settings.dailyNewLimit(), learned, reviews, newItems);
    }

    private static List<PlanItem> items(List<KnowledgePointSummary> kps) {
        return kps.stream().map(kp -> new PlanItem(kp.id(), kp.title(), kp.type())).toList();
    }
}
