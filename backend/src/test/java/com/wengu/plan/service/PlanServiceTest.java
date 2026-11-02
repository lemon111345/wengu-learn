package com.wengu.plan.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.plan.dto.CompletedItem;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.plan.dto.UpcomingDay;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.user.dto.UpdateSettingsRequest;
import com.wengu.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 每日计划（决策 016）。真实 MySQL，时钟可以手动拨动。
 * 默认设置：Asia/Shanghai、4 点分界、每天新学 3 个。默认时刻为北京时间 10 月 29 日 10:00，
 * 这个学习日是 [10-28T20:00Z, 10-29T20:00Z)。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-10-28T20:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-10-29T20:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @Autowired
    private PlanService planService;

    @Autowired
    private UserSettingsService settingsService;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private KpSourceMapper kpSourceMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        // 空库上 V7 删掉了默认用户和它的设置（决策 017），用户 1 没有设置时先插入一行
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        settingsService.update(1, new UpdateSettingsRequest("Asia/Shanghai", 4, 3));
        reviewLogMapper.delete(null);
        cardStateMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);
    }

    private void setClock(Instant now) {
        ((MutableClock) clock).set(now);
    }

    private KnowledgePoint kp(long userId, String title, int sortOrder, KnowledgePointStatus status) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle(title);
        kp.setSummary("要点");
        kp.setStatus(status);
        kp.setSortOrder(sortOrder);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        return kp;
    }

    /** 一个新知识：有新卡，从未复习过。 */
    private KnowledgePoint newKp(String title, int sortOrder) {
        return newKp(1, title, sortOrder, KnowledgePointStatus.ACTIVE);
    }

    private KnowledgePoint newKp(long userId, String title, int sortOrder, KnowledgePointStatus status) {
        KnowledgePoint kp = kp(userId, title, sortOrder, status);
        cardStateMapper.insert(CardState.of(kp.getId(), userId, Card.newCard(NOW.minus(Duration.ofDays(10)))));
        return kp;
    }

    /** 一个复习过的知识点，在 due 到期。 */
    private KnowledgePoint reviewedKp(String title, int sortOrder, Instant due) {
        return reviewedKp(1, title, sortOrder, due, KnowledgePointStatus.ACTIVE);
    }

    private KnowledgePoint reviewedKp(long userId, String title, int sortOrder, Instant due, KnowledgePointStatus status) {
        KnowledgePoint kp = kp(userId, title, sortOrder, status);
        cardStateMapper.insert(CardState.of(kp.getId(), userId,
                new Card(State.REVIEW, null, 5.0, 5.0, due, due.minus(Duration.ofDays(5)))));
        return kp;
    }

    /** 模拟 #20 的评分：第一次学习某个知识点，写流水并把它变成复习过的卡。 */
    private void firstReview(KnowledgePoint kp, Instant at) {
        ReviewLog log = new ReviewLog();
        log.setUserId(kp.getUserId());
        log.setKpId(kp.getId());
        log.setRatingUser(Rating.GOOD);
        log.setStateBefore(State.LEARNING);
        log.setFirstReview(true);
        log.setReviewedAt(at);
        reviewLogMapper.insert(log);
        cardStateMapper.updateById(CardState.of(kp.getId(), kp.getUserId(),
                new Card(State.REVIEW, null, 3.0, 5.0, at.plus(Duration.ofDays(3)), at)));
    }

    private static List<String> titles(List<PlanItem> items) {
        return items.stream().map(PlanItem::title).toList();
    }

    @Test
    void 先复习后新学且各自按学习顺序排列() {
        reviewedKp("昨天到期", 3, NOW.minus(Duration.ofDays(1)));
        reviewedKp("今天晚些时候到期", 1, DAY_END.minusMillis(1));
        reviewedKp("明天才到期", 2, DAY_END);
        // 故意倒序插入，检查按 sort_order 而不是 id 排列
        for (int i = 5; i >= 1; i--) {
            newKp("新知识 " + i, 10 + i);
        }

        TodayPlan plan = planService.today(1);

        assertThat(plan.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(titles(plan.reviews())).containsExactly("今天晚些时候到期", "昨天到期");
        assertThat(titles(plan.newItems())).containsExactly("新知识 1", "新知识 2", "新知识 3");
        assertThat(plan.dailyNewLimit()).isEqualTo(3);
        assertThat(plan.newLearnedToday()).isZero();
    }

    @Test
    void 今天已学过新知识后再次打开名额减少() {
        KnowledgePoint first = newKp("新知识 1", 1);
        newKp("新知识 2", 2);
        newKp("新知识 3", 3);
        newKp("新知识 4", 4);
        KnowledgePoint yesterday = newKp("昨天学的", 0);
        firstReview(yesterday, DAY_START.minusMillis(1));

        firstReview(first, NOW.minus(Duration.ofHours(1)));
        TodayPlan plan = planService.today(1);

        // 今天学了 1 个，昨天学的不算；学过的也不再是新知识
        assertThat(plan.newLearnedToday()).isEqualTo(1);
        assertThat(titles(plan.newItems())).containsExactly("新知识 2", "新知识 3");
    }

    @Test
    void 跨过分界时刻后名额恢复() {
        KnowledgePoint first = newKp("新知识 1", 1);
        newKp("新知识 2", 2);
        newKp("新知识 3", 3);
        newKp("新知识 4", 4);
        firstReview(first, NOW);

        // 北京时间 10 月 30 日 03:59：还是 10 月 29 日这个学习日
        setClock(DAY_END.minus(Duration.ofMinutes(1)));
        TodayPlan before = planService.today(1);
        assertThat(before.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(before.newLearnedToday()).isEqualTo(1);
        assertThat(before.newItems()).hasSize(2);

        // 04:00：新的学习日，名额恢复
        setClock(DAY_END);
        TodayPlan after = planService.today(1);
        assertThat(after.date()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(after.newLearnedToday()).isZero();
        assertThat(titles(after.newItems())).containsExactly("新知识 2", "新知识 3", "新知识 4");
    }

    @Test
    void 用户时区与UTC不在同一天() {
        KnowledgePoint learned = newKp("凌晨学的", 1);
        newKp("新知识 2", 2);
        // UTC 10 月 28 日 20:30，北京时间已是 10 月 29 日 04:30
        setClock(Instant.parse("2026-10-28T20:30:00Z"));
        firstReview(learned, Instant.parse("2026-10-28T20:10:00Z"));

        TodayPlan plan = planService.today(1);

        assertThat(plan.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(plan.newLearnedToday()).isEqualTo(1);
        assertThat(plan.completed()).extracting(CompletedItem::title).containsExactly("凌晨学的");

        // 同一时刻，纽约还是 10 月 28 日下午，这个学习日从纽约 10 月 28 日 04:00 开始，同样算今天学的
        settingsService.update(1, new UpdateSettingsRequest("America/New_York", null, null));
        TodayPlan newYork = planService.today(1);
        assertThat(newYork.date()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(newYork.newLearnedToday()).isEqualTo(1);
    }

    @Test
    void 修改每天新学数后立即生效() {
        for (int i = 1; i <= 6; i++) {
            newKp("新知识 " + i, i);
        }
        reviewedKp("到期复习", 0, NOW);
        assertThat(planService.today(1).newItems()).hasSize(3);

        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
        assertThat(planService.today(1).newItems()).hasSize(5);

        // 0：只复习不学新的
        settingsService.update(1, new UpdateSettingsRequest(null, null, 0));
        TodayPlan none = planService.today(1);
        assertThat(none.newItems()).isEmpty();
        assertThat(titles(none.reviews())).containsExactly("到期复习");
    }

    @Test
    void 今天已学数超过新的上限时名额为零而不是负数() {
        KnowledgePoint a = newKp("新知识 1", 1);
        KnowledgePoint b = newKp("新知识 2", 2);
        newKp("新知识 3", 3);
        firstReview(a, NOW);
        firstReview(b, NOW);

        settingsService.update(1, new UpdateSettingsRequest(null, null, 1));
        TodayPlan plan = planService.today(1);

        assertThat(plan.newLearnedToday()).isEqualTo(2);
        assertThat(plan.newItems()).isEmpty();
    }

    @Test
    void 暂停的知识点和其他用户的知识点被排除() {
        reviewedKp(1, "暂停的复习", 1, NOW, KnowledgePointStatus.SUSPENDED);
        newKp(1, "暂停的新知识", 2, KnowledgePointStatus.SUSPENDED);
        reviewedKp(2, "其他用户的复习", 1, NOW, KnowledgePointStatus.ACTIVE);
        newKp(2, "其他用户的新知识", 1, KnowledgePointStatus.ACTIVE);
        reviewedKp("正常的复习", 3, NOW);
        newKp("正常的新知识", 4);

        TodayPlan plan = planService.today(1);

        assertThat(titles(plan.reviews())).containsExactly("正常的复习");
        assertThat(titles(plan.newItems())).containsExactly("正常的新知识");
        // 知识点总数算暂停的，不算其他用户的
        assertThat(plan.knowledgePointCount()).isEqualTo(4);
    }

    @Test
    void 知识点总数从零开始且只收录的也算() {
        assertThat(planService.today(1).knowledgePointCount()).isZero();

        // 只有不参与调度的知识点（如系统设计，没有 card_state）：计划为空，但不是空状态
        kp(1, "只收录的", 1, KnowledgePointStatus.ACTIVE);
        TodayPlan plan = planService.today(1);
        assertThat(plan.reviews()).isEmpty();
        assertThat(plan.newItems()).isEmpty();
        assertThat(plan.knowledgePointCount()).isEqualTo(1);
    }

    /** 写一条评分流水，不改调度状态：“今天已完成”只看流水。 */
    private void log(KnowledgePoint kp, Instant at, Rating rating, boolean first) {
        ReviewLog log = new ReviewLog();
        log.setUserId(kp.getUserId());
        log.setKpId(kp.getId());
        log.setRatingUser(rating);
        log.setStateBefore(first ? State.LEARNING : State.REVIEW);
        log.setFirstReview(first);
        log.setReviewedAt(at);
        reviewLogMapper.insert(log);
    }

    @Test
    void 今天已完成的按完成时间排列并带评分和是否新学() {
        KnowledgePoint learned = newKp("新学的", 1);
        KnowledgePoint reviewed = reviewedKp("复习的", 2, NOW.plus(Duration.ofDays(3)));
        log(reviewed, NOW.minus(Duration.ofMinutes(30)), Rating.HARD, false);
        log(learned, NOW.minus(Duration.ofHours(1)), Rating.GOOD, true);

        TodayPlan plan = planService.today(1);

        assertThat(plan.completed()).containsExactly(
                new CompletedItem(learned.getId(), "新学的", KnowledgePointType.MECHANISM, Rating.GOOD, true),
                new CompletedItem(reviewed.getId(), "复习的", KnowledgePointType.MECHANISM, Rating.HARD, false));
    }

    @Test
    void 今天已完成的按学习日的分界时刻划分() {
        KnowledgePoint before = reviewedKp("北京时间凌晨3点59分评的", 1, NOW.plus(Duration.ofDays(3)));
        KnowledgePoint atStart = reviewedKp("北京时间凌晨4点整评的", 2, NOW.plus(Duration.ofDays(3)));
        KnowledgePoint tomorrow = reviewedKp("明天凌晨4点评的", 3, NOW.plus(Duration.ofDays(3)));
        // 10 月 29 日这个学习日是 [10-28T20:00Z, 10-29T20:00Z)，UTC 日期和北京时间不在同一天
        log(before, DAY_START.minusSeconds(1), Rating.GOOD, false);
        log(atStart, DAY_START, Rating.GOOD, false);
        log(tomorrow, DAY_END, Rating.GOOD, false);

        TodayPlan plan = planService.today(1);

        assertThat(plan.completed()).extracting(CompletedItem::title).containsExactly("北京时间凌晨4点整评的");
    }

    @Test
    void 同一个知识点今天评了两次时取最后一次() {
        KnowledgePoint a = reviewedKp("评了两次的", 1, NOW.plus(Duration.ofDays(3)));
        KnowledgePoint b = reviewedKp("评了一次的", 2, NOW.plus(Duration.ofDays(3)));
        log(a, NOW.minus(Duration.ofHours(3)), Rating.AGAIN, false);
        log(b, NOW.minus(Duration.ofHours(2)), Rating.GOOD, false);
        log(a, NOW.minus(Duration.ofHours(1)), Rating.EASY, false);

        TodayPlan plan = planService.today(1);

        assertThat(plan.completed()).extracting(CompletedItem::title, CompletedItem::rating)
                .containsExactly(tuple("评了一次的", Rating.GOOD), tuple("评了两次的", Rating.EASY));
    }

    @Test
    void 今天已完成的包括暂停的但不包括其他用户的() {
        KnowledgePoint suspended = reviewedKp(1, "学完后暂停的", 1, NOW.plus(Duration.ofDays(3)),
                KnowledgePointStatus.SUSPENDED);
        KnowledgePoint others = reviewedKp(2, "其他用户的", 1, NOW.plus(Duration.ofDays(3)),
                KnowledgePointStatus.ACTIVE);
        log(suspended, NOW, Rating.GOOD, false);
        log(others, NOW, Rating.GOOD, false);

        TodayPlan plan = planService.today(1);

        assertThat(plan.completed()).extracting(CompletedItem::title).containsExactly("学完后暂停的");
    }

    private static List<Integer> counts(TodayPlan plan) {
        return plan.upcomingReviews().stream().map(UpcomingDay::count).toList();
    }

    @Test
    void 接下来7天从明天开始按学习日分组() {
        // 今天是 10 月 29 日这个学习日，到 DAY_END（北京时间 10 月 30 日 04:00）为止
        reviewedKp("今天最后一刻到期", 1, DAY_END.minusSeconds(1));
        reviewedKp("已经过期", 2, NOW.minus(Duration.ofDays(2)));
        reviewedKp("明天第一刻到期", 3, DAY_END);
        reviewedKp("明天最后一刻到期", 4, DAY_END.plus(Duration.ofDays(1)).minusSeconds(1));
        reviewedKp("后天到期", 5, DAY_END.plus(Duration.ofDays(1)));
        reviewedKp("第7天最后一刻到期", 6, DAY_END.plus(Duration.ofDays(7)).minusSeconds(1));
        reviewedKp("第8天到期", 7, DAY_END.plus(Duration.ofDays(7)));
        newKp("新知识", 8);

        TodayPlan plan = planService.today(1);

        assertThat(plan.upcomingReviews()).extracting(UpcomingDay::date)
                .containsExactly(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                        LocalDate.of(2026, 11, 5));
        assertThat(counts(plan)).containsExactly(2, 1, 0, 0, 0, 0, 1);
        // 今天到期的和已经过期的在今天的复习列表里，不重复算
        assertThat(titles(plan.reviews())).containsExactly("今天最后一刻到期", "已经过期");
    }

    @Test
    void 接下来7天不算暂停的和其他用户的() {
        reviewedKp(1, "暂停的", 1, DAY_END, KnowledgePointStatus.SUSPENDED);
        reviewedKp(2, "其他用户的", 1, DAY_END, KnowledgePointStatus.ACTIVE);
        reviewedKp("正常的", 2, DAY_END);

        assertThat(counts(planService.today(1))).containsExactly(1, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void 修改分界时刻后接下来7天的分组跟着变() {
        // 北京时间 10 月 30 日 01:00：4 点分界时属于 10 月 29 日（今天），0 点分界时属于 10 月 30 日（明天）
        reviewedKp("凌晨1点到期", 1, Instant.parse("2026-10-29T17:00:00Z"));

        assertThat(counts(planService.today(1))).containsExactly(0, 0, 0, 0, 0, 0, 0);

        settingsService.update(1, new UpdateSettingsRequest(null, 0, null));
        assertThat(counts(planService.today(1))).containsExactly(1, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void 夏令时结束的那一天是25小时() {
        // 纽约 2026 年 11 月 1 日 02:00 夏令时结束。10 月 31 日这个学习日从 10-31 04:00 EDT（08:00Z）
        // 到 11-01 04:00 EST（09:00Z），共 25 小时
        settingsService.update(1, new UpdateSettingsRequest("America/New_York", 4, null));
        setClock(Instant.parse("2026-10-29T14:00:00Z"));
        // 11-01 03:30 EST：按 24 小时切分会落到 11 月 1 日，按学习日应属于 10 月 31 日
        reviewedKp("夏令时结束后凌晨到期", 1, Instant.parse("2026-11-01T08:30:00Z"));
        reviewedKp("11月1日的学习日开始时到期", 2, Instant.parse("2026-11-01T09:00:00Z"));

        TodayPlan plan = planService.today(1);

        assertThat(plan.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(counts(plan)).containsExactly(0, 1, 1, 0, 0, 0, 0);
    }
}
