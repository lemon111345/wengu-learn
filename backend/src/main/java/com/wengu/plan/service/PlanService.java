package com.wengu.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.chat.dto.StudyRatedEvent;
import com.wengu.chat.service.StudySessionService;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.plan.dto.CompletedItem;
import com.wengu.plan.dto.PickRequest;
import com.wengu.plan.dto.PickResult;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.plan.dto.UpcomingDay;
import com.wengu.plan.entity.PlanPick;
import com.wengu.plan.entity.StudyDayCompletion;
import com.wengu.plan.mapper.PlanPickMapper;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 每日计划，每次打开时实时计算，不建表：它是派生数据，事实已经在 card_state 和 review_log 里
 * （技术方案“每日计划的计算逻辑”，决策 016）。计划变空时写一条“当天完成”的记录（决策 020），那是事实，不是计划的快照。
 * 用户自己加入今天的题存在 plan_pick（决策 052），那是算不出来的用户意图，计划仍然实时计算。
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
    private final PlanPickMapper planPickMapper;
    private final Clock clock;

    public PlanService(UserSettingsService settingsService, ReviewService reviewService,
                       KnowledgePointService knowledgePointService, StudySessionService sessionService,
                       StudyDayCompletionMapper completionMapper, PlanPickMapper planPickMapper, Clock clock) {
        this.settingsService = settingsService;
        this.reviewService = reviewService;
        this.knowledgePointService = knowledgePointService;
        this.sessionService = sessionService;
        this.completionMapper = completionMapper;
        this.planPickMapper = planPickMapper;
        this.clock = clock;
    }

    public TodayPlan today(long userId) {
        // 1. 根据用户时区和 day_start_hour 算出“今天”
        SettingsView settings = settingsService.get(userId);
        StudyDay day = StudyDay.of(Instant.now(clock), settings.zoneId(), settings.dayStartHour());

        // 2. 到期复习：今天结束前到期的，包括今天晚些时候才到期的。全部列出，不受每天学习总量限制
        List<PlanItem> reviews = items(knowledgePointService.activeInOrder(userId,
                reviewService.dueKpIds(userId, day.end())));

        // 5. 新知识的候选：去掉暂停的，按学习顺序。全部待学的总数还要用来算“还要几天学完”
        List<KnowledgePointSummary> allNew = knowledgePointService.activeInOrder(userId, reviewService.newKpIds(userId));

        // 6. 新学列表的前两部分（决策 052）：学到一半的，今天自己加入的。它们先占名额，比名额多时照样全部列出
        List<KnowledgePointSummary> firstParts = startedAndPicked(userId, day, allNew);

        // 3、4. 计划派发的只补足总量剩下的名额。今天已经评过分的也要扣：不扣新学的，下午再打开时会再派一批；
        // 不扣复习的，每完成一个复习，名额就会多出一个
        int learned = reviewService.countFirstReviews(userId, day.start(), day.end());
        List<ReviewRecord> todayRecords = reviewService.reviewsBetween(userId, day.start(), day.end());
        int remaining = Math.max(0, settings.dailyNewLimit() - usedToday(reviews, todayRecords, firstParts));

        Set<Long> taken = ids(firstParts);
        List<PlanItem> newItems = Stream.concat(firstParts.stream(),
                        allNew.stream().filter(kp -> !taken.contains(kp.id())).limit(remaining))
                .map(PlanService::item)
                .toList();

        // 7. 先复习，后新学；另附今天已完成的，供页面显示对勾、评分和进度
        List<CompletedItem> completed = completed(userId, todayRecords);
        if (reviews.isEmpty() && newItems.isEmpty() && !completed.isEmpty()) {
            recordCompletion(userId, day.date());
        }
        return new TodayPlan(day.date(), settings.dailyNewLimit(), learned, reviews, newItems,
                completed, upcomingReviews(userId, day, settings), knowledgePointService.count(userId),
                sessionService.activeSecondsBetween(userId, day.start(), day.end()) / 60,
                daysToFinish(allNew.size(), newItems.size(), settings.dailyNewLimit()),
                currentStreak(userId, day, settings), studiedMinutesThisWeek(userId, day, settings));
    }

    /**
     * 把知识点加入今天（#128，决策 052）。只加入其中待学的；混着学过的、暂停的不整批失败，各自计数，前端据此提示。
     * <p>
     * “已经在今天”直接用 {@link #today} 的新学列表判断，规则只有一份。它可能顺带写一条“当天完成”，那本来就是事实。
     * 同一批按学习顺序写入：前端勾选的顺序不固定，这样今天页上同一批的先后是稳定的。
     *
     * @throws BusinessException 没有选择时为 BAD_REQUEST
     */
    public PickResult pick(long userId, PickRequest request) {
        if (request == null || request.kpIds() == null || request.kpIds().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有选择知识点");
        }
        Set<Long> requested = new HashSet<>(request.kpIds());
        TodayPlan plan = today(userId);
        Set<Long> inToday = plan.newItems().stream().map(PlanItem::kpId).collect(Collectors.toSet());

        int added = 0;
        int already = 0;
        Instant now = Instant.now(clock);
        for (KnowledgePointSummary kp : knowledgePointService.activeInOrder(userId, reviewService.newKpIds(userId))) {
            if (!requested.contains(kp.id())) {
                continue;
            }
            if (inToday.contains(kp.id())) {
                already++;
            } else if (insertPick(userId, kp.id(), plan.date(), now)) {
                added++;
            } else {
                already++;
            }
        }
        return new PickResult(added, already, requested.size() - added - already);
    }

    /** @return false 表示同时有别的请求先加入了这道题（唯一索引） */
    private boolean insertPick(long userId, long kpId, LocalDate date, Instant now) {
        PlanPick pick = new PlanPick();
        pick.setUserId(userId);
        pick.setKpId(kpId);
        pick.setStudyDate(date);
        pick.setCreatedAt(now);
        try {
            planPickMapper.insert(pick);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 新学列表的前两部分（决策 052），同一个知识点只出现一次：
     * <ol>
     *   <li>学到一半的：待学并且有学习会话，按学习顺序。不论哪天开始的，评分前一直在</li>
     *   <li>今天加入的：今天学习日的加入记录中仍然待学的，按加入的先后（id 的顺序）</li>
     * </ol>
     */
    private List<KnowledgePointSummary> startedAndPicked(long userId, StudyDay day, List<KnowledgePointSummary> allNew) {
        Map<Long, KnowledgePointSummary> byId = new LinkedHashMap<>();
        allNew.forEach(kp -> byId.put(kp.id(), kp));

        Set<Long> started = sessionService.kpIdsWithStudySession(userId, byId.keySet());
        List<KnowledgePointSummary> result = new ArrayList<>(
                allNew.stream().filter(kp -> started.contains(kp.id())).toList());
        planPickMapper.selectList(new LambdaQueryWrapper<PlanPick>()
                        .select(PlanPick::getKpId)
                        .eq(PlanPick::getUserId, userId)
                        .eq(PlanPick::getStudyDate, day.date())
                        .orderByAsc(PlanPick::getId))
                .stream()
                .map(PlanPick::getKpId)
                .filter(id -> byId.containsKey(id) && !started.contains(id))
                .forEach(id -> result.add(byId.get(id)));
        return result;
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
     * 今天占名额的知识点数：还没完成的到期复习，今天评过分的（新学和复习都算），学到一半的和今天加入还没学的（决策 052）。
     * 取并集，同一个知识点今天评了多次也只占一个名额。流水不按知识点状态过滤：学完后删除或暂停的，仍然占名额。
     */
    private static int usedToday(List<PlanItem> reviews, List<ReviewRecord> todayRecords,
                                 List<KnowledgePointSummary> startedAndPicked) {
        Set<Long> used = new HashSet<>();
        reviews.forEach(item -> used.add(item.kpId()));
        todayRecords.forEach(record -> used.add(record.kpId()));
        startedAndPicked.forEach(kp -> used.add(kp.id()));
        return used.size();
    }

    /**
     * 还要几个学习日学完全部待学的新知识：今天新学列表里的先学完（算一天），其余每天学 dailyNewLimit 个。
     * 今天的新学列表为空时从明天算起。没有算以后的复习，所以是下限（{@link TodayPlan#daysToFinish()}）。
     *
     * @param newCount       待学的新知识总数（不含暂停的），包括今天计划里还没学的
     * @param newToday       今天新学列表的个数（学到一半的、今天加入的、计划派发的，决策 052）
     * @return 每天新学为 0 或没有待学时为 null
     */
    static Integer daysToFinish(int newCount, int newToday, int dailyNewLimit) {
        if (newCount == 0 || dailyNewLimit == 0) {
            return null;
        }
        int afterToday = Math.max(0, newCount - newToday);
        int today = newToday > 0 ? 1 : 0;
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
        return kps.stream().map(PlanService::item).toList();
    }

    private static PlanItem item(KnowledgePointSummary kp) {
        return new PlanItem(kp.id(), kp.title(), kp.type());
    }

    private static Set<Long> ids(List<KnowledgePointSummary> kps) {
        return kps.stream().map(KnowledgePointSummary::id).collect(Collectors.toSet());
    }
}
