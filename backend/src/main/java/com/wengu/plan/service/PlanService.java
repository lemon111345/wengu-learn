package com.wengu.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.chat.dto.StudyRatedEvent;
import com.wengu.chat.service.StudySessionService;
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.plan.dto.CompletedItem;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.plan.dto.UpcomingDay;
import com.wengu.plan.entity.StudyDayCompletion;
import com.wengu.plan.mapper.StudyDayCompletionMapper;
import com.wengu.review.dto.DueCard;
import com.wengu.review.dto.ReviewRecord;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 每日计划，每次打开时实时计算，不建表：它是派生数据，事实已经在 card_state 和 review_log 里
 * （技术方案“每日计划的计算逻辑”，决策 016）。计划变空时写一条“当天完成”的记录（决策 020），那是事实，不是计划的快照。
 * <p>
 * 每个模块只读自己的表：review 给出候选 kp_id，knowledge 过滤掉暂停的并排序，user 提供设置（决策 002）。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final UserSettingsService settingsService;
    private final ReviewService reviewService;
    private final KnowledgePointService knowledgePointService;
    private final StudySessionService sessionService;
    private final StudyDayCompletionMapper completionMapper;
    private final Clock clock;

    public PlanService(UserSettingsService settingsService, ReviewService reviewService,
                       KnowledgePointService knowledgePointService, StudySessionService sessionService,
                       StudyDayCompletionMapper completionMapper, Clock clock) {
        this.settingsService = settingsService;
        this.reviewService = reviewService;
        this.knowledgePointService = knowledgePointService;
        this.sessionService = sessionService;
        this.completionMapper = completionMapper;
        this.clock = clock;
    }

    public TodayPlan today(long userId) {
        // 1. 根据用户时区和 day_start_hour 算出“今天”
        SettingsView settings = settingsService.get(userId);
        StudyDay day = StudyDay.of(Instant.now(clock), settings.zoneId(), settings.dayStartHour());

        // 2. 到期复习：今天结束前到期的，包括今天晚些时候才到期的。全部列出，不受每天学习总量限制
        List<PlanItem> reviews = items(knowledgePointService.activeInOrder(userId,
                reviewService.dueKpIds(userId, day.end())));

        // 3、4. 新学只补足总量剩下的名额。今天已经评过分的也要扣：不扣新学的，下午再打开时会再派一批；
        // 不扣复习的，每完成一个复习，名额就会多出一个
        int learned = reviewService.countFirstReviews(userId, day.start(), day.end());
        List<ReviewRecord> todayRecords = reviewService.reviewsBetween(userId, day.start(), day.end());
        int remaining = Math.max(0, settings.dailyNewLimit() - usedToday(reviews, todayRecords));

        // 5、6. 新知识：去掉暂停的，按学习顺序取前 remaining 个。全部待学的总数还要用来算“还要几天学完”
        List<KnowledgePointSummary> allNew = knowledgePointService.activeInOrder(userId, reviewService.newKpIds(userId));
        List<PlanItem> newItems = items(allNew).stream().limit(remaining).toList();

        // 7. 先复习，后新学；另附今天已完成的，供页面显示对勾、评分和进度
        List<CompletedItem> completed = completed(userId, todayRecords);
        if (reviews.isEmpty() && newItems.isEmpty() && !completed.isEmpty()) {
            recordCompletion(userId, day.date());
        }
        return new TodayPlan(day.date(), settings.dailyNewLimit(), learned, reviews, newItems,
                completed, upcomingReviews(userId, day, settings), knowledgePointService.count(userId),
                sessionService.activeSecondsBetween(userId, day.start(), day.end()) / 60,
                daysToFinish(allNew.size(), remaining, settings.dailyNewLimit()),
                currentStreak(userId, day, settings), studiedMinutesThisWeek(userId, day, settings));
    }

    /**
     * 评分后检查今天是否学完（决策 041）：学完最后一个直接关页面时，不会再有人打开今天页。
     * 这个监听器和评分在同一个线程，异常要在这里挡住：评分已经成功，不能让前端看到失败。
     * 没写上也没关系，下次打开今天页时还会检查。
     */
    @EventListener
    public void onStudyRated(StudyRatedEvent event) {
        try {
            today(event.userId());
        } catch (RuntimeException e) {
            log.warn("评分后检查当天完成失败，userId={}", event.userId(), e);
        }
    }

    /** 连续学习天数（决策 020）。只在缺完成记录的日子才查评分流水 */
    private int currentStreak(long userId, StudyDay today, SettingsView settings) {
        Set<LocalDate> completedDates = completionMapper.selectList(new LambdaQueryWrapper<StudyDayCompletion>()
                        .select(StudyDayCompletion::getStudyDate)
                        .eq(StudyDayCompletion::getUserId, userId))
                .stream()
                .map(StudyDayCompletion::getStudyDate)
                .collect(Collectors.toSet());
        return StudyStreak.count(today.date(), completedDates, date -> missedReview(userId,
                StudyDay.ofDate(date, settings.zoneId(), settings.dayStartHour())));
    }

    /** review 不知道知识点是否暂停，由 knowledge 过滤（决策 002）。暂停的历史没有记录，按现在的状态算（决策 041） */
    private boolean missedReview(long userId, StudyDay day) {
        return !knowledgePointService.activeInOrder(userId,
                reviewService.missedDueKpIds(userId, day.start(), day.end())).isEmpty();
    }

    /** 本周从周一所在的学习日开始，到今天的学习日结束 */
    private int studiedMinutesThisWeek(long userId, StudyDay today, SettingsView settings) {
        LocalDate monday = today.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant weekStart = StudyDay.ofDate(monday, settings.zoneId(), settings.dayStartHour()).start();
        return sessionService.activeSecondsBetween(userId, weekStart, today.end()) / 60;
    }

    /**
     * 今天占名额的知识点数：还没完成的到期复习，加上今天评过分的（新学和复习都算）。
     * 取并集，同一个知识点今天评了多次也只占一个名额。流水不按知识点状态过滤：学完后删除或暂停的，仍然占名额。
     */
    private static int usedToday(List<PlanItem> reviews, List<ReviewRecord> todayRecords) {
        Set<Long> used = new HashSet<>();
        reviews.forEach(item -> used.add(item.kpId()));
        todayRecords.forEach(record -> used.add(record.kpId()));
        return used.size();
    }

    /**
     * 还要几个学习日学完全部待学的新知识：今天剩下的名额先用掉（算一天），其余每天学 dailyNewLimit 个。
     * 今天的名额已经用完时从明天算起。没有算以后的复习，所以是下限（{@link TodayPlan#daysToFinish()}）。
     *
     * @param newCount       待学的新知识总数（不含暂停的），包括今天计划里还没学的
     * @param remainingToday 今天还剩的新学名额
     * @return 每天新学为 0 或没有待学时为 null
     */
    static Integer daysToFinish(int newCount, int remainingToday, int dailyNewLimit) {
        if (newCount == 0 || dailyNewLimit == 0) {
            return null;
        }
        int afterToday = Math.max(0, newCount - remainingToday);
        int today = remainingToday > 0 ? 1 : 0;
        return today + (afterToday + dailyNewLimit - 1) / dailyNewLimit;
    }

    /**
     * 记下“当天完成”（决策 020）：今天的计划变空、并且今天确实学过。这是已经发生的事实，不是计划的快照。
     * <p>
     * 在算计划时写：判断计划是否变空的规则只有这一份。评分后由 {@link #onStudyRated} 算一次，
     * 暂停或删除最后一个知识点时由下次打开今天页算。重复写由唯一索引挡住。
     */
    private void recordCompletion(long userId, LocalDate date) {
        if (completionMapper.exists(new LambdaQueryWrapper<StudyDayCompletion>()
                .eq(StudyDayCompletion::getUserId, userId)
                .eq(StudyDayCompletion::getStudyDate, date))) {
            return;
        }
        StudyDayCompletion completion = new StudyDayCompletion();
        completion.setUserId(userId);
        completion.setStudyDate(date);
        completion.setCompletedAt(Instant.now(clock));
        try {
            completionMapper.insert(completion);
        } catch (DuplicateKeyException e) {
            // 两个请求同时发现计划变空，先写进去的为准
        }
    }

    /** 接下来几个学习日要复习的：从明天起，不含今天。今天到期的、已经过期的都在今天的复习列表里。 */
    static final int UPCOMING_DAYS = 7;

    /**
     * 接下来 {@link #UPCOMING_DAYS} 个学习日每天到期的复习数。每一天按用户的时区和 day_start_hour 划分，
     * 和“今天”的算法一致，遇到夏令时可能是 23 或 25 小时。暂停的不算。
     */
    private List<UpcomingDay> upcomingReviews(long userId, StudyDay today, SettingsView settings) {
        List<StudyDay> days = IntStream.rangeClosed(1, UPCOMING_DAYS)
                .mapToObj(i -> StudyDay.ofDate(today.date().plusDays(i), settings.zoneId(), settings.dayStartHour()))
                .toList();
        List<DueCard> due = reviewService.dueBetween(userId, days.getFirst().start(), days.getLast().end());
        // review 不知道知识点是否暂停，由 knowledge 过滤（决策 002）；这里只用 id，顺序用不上
        Set<Long> active = knowledgePointService.activeInOrder(userId, due.stream().map(DueCard::kpId).toList())
                .stream().map(KnowledgePointSummary::id).collect(Collectors.toSet());

        return days.stream().map(day -> new UpcomingDay(day.date(), (int) due.stream()
                        .filter(card -> active.contains(card.kpId()))
                        .filter(card -> !card.due().isBefore(day.start()) && card.due().isBefore(day.end()))
                        .count()))
                .toList();
    }

    /** 今天已完成的：来自今天学习日内的评分流水，同一个知识点评了多次时取最后一次，按完成时间排列。 */
    private List<CompletedItem> completed(long userId, List<ReviewRecord> todayRecords) {
        Map<Long, ReviewRecord> latest = new LinkedHashMap<>();
        for (ReviewRecord r : todayRecords) {
            // 先删再放：再次评分的知识点移到最后，顺序跟着最后一次评分走
            latest.remove(r.kpId());
            latest.put(r.kpId(), r);
        }
        Map<Long, KnowledgePointSummary> kps = knowledgePointService.summaries(userId, latest.keySet());
        return latest.values().stream()
                .filter(r -> kps.containsKey(r.kpId()))
                .map(r -> {
                    KnowledgePointSummary kp = kps.get(r.kpId());
                    return new CompletedItem(kp.id(), kp.title(), kp.type(), r.rating(), r.firstReview(), r.sessionId());
                })
                .toList();
    }

    private static List<PlanItem> items(List<KnowledgePointSummary> kps) {
        return kps.stream().map(kp -> new PlanItem(kp.id(), kp.title(), kp.type())).toList();
    }
}
