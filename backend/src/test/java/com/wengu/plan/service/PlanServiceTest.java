package com.wengu.plan.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.plan.dto.CompletedItem;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.plan.dto.UpcomingDay;
import com.wengu.plan.entity.StudyDayCompletion;
import com.wengu.plan.mapper.StudyDayCompletionMapper;
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
 * 默认设置：Asia/Shanghai、4 点分界、每天学 3 个（含复习）。默认时刻为北京时间 10 月 29 日 10:00，
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
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private StudyDayCompletionMapper completionMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        // 空库上 V7 删掉了默认用户和它的设置（决策 017），用户 1 没有设置时先插入一行
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        settingsService.update(1, new UpdateSettingsRequest("Asia/Shanghai", 4, 3));
        completionMapper.delete(null);
        messageMapper.delete(null);
        sessionMapper.delete(null);
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
        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
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
        assertThat(plan.dailyNewLimit()).isEqualTo(5);
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
    void 自己挑一个列表外的新知识_算今天学的且列表末尾少一个() {
        newKp("新知识 1", 1);
        newKp("新知识 2", 2);
        newKp("新知识 3", 3);
        KnowledgePoint picked = newKp("知识库里挑的", 4);
        assertThat(titles(planService.today(1).newItems())).containsExactly("新知识 1", "新知识 2", "新知识 3");

        firstReview(picked, NOW.minus(Duration.ofHours(1)));
        TodayPlan plan = planService.today(1);

        assertThat(plan.newLearnedToday()).isEqualTo(1);
        assertThat(titles(plan.newItems())).containsExactly("新知识 1", "新知识 2");
        assertThat(plan.completed()).extracting(CompletedItem::title).containsExactly("知识库里挑的");
    }

    @Test
    void 名额用完后自己挑的仍然算今天学的_不再派新知识() {
        firstReview(newKp("新知识 1", 1), NOW.minus(Duration.ofHours(4)));
        firstReview(newKp("新知识 2", 2), NOW.minus(Duration.ofHours(3)));
        firstReview(newKp("新知识 3", 3), NOW.minus(Duration.ofHours(2)));
        KnowledgePoint picked = newKp("知识库里挑的", 4);
        newKp("新知识 5", 5);
        assertThat(planService.today(1).newItems()).isEmpty();

        firstReview(picked, NOW.minus(Duration.ofHours(1)));
        TodayPlan plan = planService.today(1);

        assertThat(plan.newLearnedToday()).isEqualTo(4);
        assertThat(plan.newItems()).isEmpty();
        assertThat(plan.completed()).hasSize(4);
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
    void 修改每天学几个后立即生效() {
        for (int i = 1; i <= 6; i++) {
            newKp("新知识 " + i, i);
        }
        reviewedKp("到期复习", 0, NOW);
        // 总量 3，复习占 1 个
        assertThat(planService.today(1).newItems()).hasSize(2);

        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
        assertThat(planService.today(1).newItems()).hasSize(4);

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
    void 到期复习全部列出不截断_超过总量时新学为零() {
        for (int i = 1; i <= 5; i++) {
            reviewedKp("复习 " + i, i, NOW.minus(Duration.ofDays(i)));
        }
        newKp("新知识", 10);
        reviewedKp("明天到期", 11, DAY_END);

        TodayPlan plan = planService.today(1);

        assertThat(plan.reviews()).hasSize(5);
        assertThat(plan.newItems()).isEmpty();
        // 接下来 7 天的预测只看复习，不受总量影响
        assertThat(counts(plan)).containsExactly(1, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void 新学只补足总量减去复习后的名额() {
        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
        reviewedKp("复习 1", 1, NOW);
        reviewedKp("复习 2", 2, NOW);
        for (int i = 1; i <= 5; i++) {
            newKp("新知识 " + i, 10 + i);
        }

        assertThat(titles(planService.today(1).newItems())).containsExactly("新知识 1", "新知识 2", "新知识 3");
    }

    @Test
    void 完成复习后新学名额不变() {
        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
        KnowledgePoint review = reviewedKp("复习 1", 1, NOW);
        reviewedKp("复习 2", 2, NOW);
        for (int i = 1; i <= 5; i++) {
            newKp("新知识 " + i, 10 + i);
        }
        assertThat(planService.today(1).newItems()).hasSize(3);

        finishReview(review);
        TodayPlan plan = planService.today(1);

        assertThat(titles(plan.reviews())).containsExactly("复习 2");
        assertThat(plan.newItems()).hasSize(3);
    }

    @Test
    void 今天的复习和已学的新知识一起占名额() {
        settingsService.update(1, new UpdateSettingsRequest(null, null, 5));
        KnowledgePoint done = reviewedKp("完成的复习", 1, NOW);
        reviewedKp("没完成的复习", 2, NOW);
        KnowledgePoint learned = newKp("学过的新知识", 3);
        for (int i = 1; i <= 5; i++) {
            newKp("新知识 " + i, 10 + i);
        }
        finishReview(done);
        firstReview(learned, NOW);

        // 总量 5：完成的复习 1、没完成的复习 1、已学的新知识 1，还剩 2 个
        assertThat(titles(planService.today(1).newItems())).containsExactly("新知识 1", "新知识 2");
    }

    @Test
    void 同一个知识点今天评了两次只占一个名额() {
        KnowledgePoint review = reviewedKp("评了两次的", 1, NOW.plus(Duration.ofDays(3)));
        log(review, NOW.minus(Duration.ofHours(2)), Rating.AGAIN, false);
        log(review, NOW.minus(Duration.ofHours(1)), Rating.GOOD, false);
        for (int i = 1; i <= 3; i++) {
            newKp("新知识 " + i, 10 + i);
        }

        assertThat(titles(planService.today(1).newItems())).containsExactly("新知识 1", "新知识 2");
    }

    @Test
    void 暂停的到期复习不占名额() {
        reviewedKp(1, "暂停的复习", 1, NOW, KnowledgePointStatus.SUSPENDED);
        for (int i = 1; i <= 3; i++) {
            newKp("新知识 " + i, 10 + i);
        }

        assertThat(planService.today(1).newItems()).hasSize(3);
    }

    @Test
    void 昨天完成的复习不占今天的名额() {
        KnowledgePoint review = reviewedKp("昨天复习的", 1, NOW.plus(Duration.ofDays(3)));
        log(review, DAY_START.minusMillis(1), Rating.GOOD, false);
        for (int i = 1; i <= 3; i++) {
            newKp("新知识 " + i, 10 + i);
        }

        assertThat(planService.today(1).newItems()).hasSize(3);
    }

    @Test
    void 名额被复习占满时_学完复习也算当天完成() {
        KnowledgePoint review = reviewedKp("复习", 1, NOW);
        settingsService.update(1, new UpdateSettingsRequest(null, null, 1));
        newKp("新知识", 2);
        assertThat(planService.today(1).newItems()).isEmpty();

        finishReview(review);
        planService.today(1);

        assertThat(completionMapper.selectCount(null)).isEqualTo(1);
    }

    /** 模拟 #20 的评分：完成一次复习，写流水并把到期日推到以后。 */
    private void finishReview(KnowledgePoint kp) {
        log(kp, NOW, Rating.GOOD, false);
        cardStateMapper.updateById(CardState.of(kp.getId(), kp.getUserId(),
                new Card(State.REVIEW, null, 8.0, 5.0, NOW.plus(Duration.ofDays(8)), NOW)));
    }

    @Test
    void 还要几天学完_今天的名额先用掉_其余按每天新学数往后排() {
        // 纯计算：(待学总数, 今天剩的名额, 每天新学数)
        assertThat(PlanService.daysToFinish(7, 3, 3)).isEqualTo(3);  // 今天 3、明天 3、后天 1
        assertThat(PlanService.daysToFinish(3, 3, 3)).isEqualTo(1);  // 今天就能学完
        assertThat(PlanService.daysToFinish(2, 3, 3)).isEqualTo(1);
        assertThat(PlanService.daysToFinish(4, 1, 3)).isEqualTo(2);  // 今天学了 2 个，剩 1 个名额
        assertThat(PlanService.daysToFinish(3, 0, 3)).isEqualTo(1);  // 今天的名额用完了，从明天算起
        assertThat(PlanService.daysToFinish(4, 0, 3)).isEqualTo(2);
        assertThat(PlanService.daysToFinish(0, 3, 3)).isNull();      // 没有待学的
        assertThat(PlanService.daysToFinish(5, 0, 0)).isNull();      // 每天新学为 0：永远学不完
    }

    @Test
    void 还要几天学完不算暂停的_并扣除今天已学的() {
        assertThat(planService.today(1).daysToFinish()).isNull();

        KnowledgePoint learned = newKp("今天学的", 0);
        for (int i = 1; i <= 7; i++) {
            newKp("新知识 " + i, i);
        }
        newKp(1, "暂停的新知识", 8, KnowledgePointStatus.SUSPENDED);
        firstReview(learned, NOW);

        // 待学 7 个（暂停的和已学的不算），今天还剩 2 个名额：今天 2、明天 3、后天 2
        assertThat(planService.today(1).daysToFinish()).isEqualTo(3);

        settingsService.update(1, new UpdateSettingsRequest(null, null, 0));
        assertThat(planService.today(1).daysToFinish()).isNull();
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
        log(kp, at, rating, first, null);
    }

    private void log(KnowledgePoint kp, Instant at, Rating rating, boolean first, Long sessionId) {
        ReviewLog log = new ReviewLog();
        log.setSessionId(sessionId);
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
                new CompletedItem(learned.getId(), "新学的", KnowledgePointType.MECHANISM, Rating.GOOD, true, null),
                new CompletedItem(reviewed.getId(), "复习的", KnowledgePointType.MECHANISM, Rating.HARD, false, null));
    }

    @Test
    void 今天已完成的带上最后一次评分的会话() {
        KnowledgePoint kp = reviewedKp("评了两次的", 1, NOW.plus(Duration.ofDays(3)));
        log(kp, NOW.minus(Duration.ofHours(2)), Rating.GOOD, false, 701L);
        log(kp, NOW.minus(Duration.ofHours(1)), Rating.HARD, false, 702L);

        assertThat(planService.today(1).completed()).extracting(CompletedItem::sessionId).containsExactly(702L);
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

    /** 知识库中删除（#25）；mergedInto 不为空时是被合并的 */
    private void delete(KnowledgePoint kp, Long mergedInto) {
        knowledgePointMapper.update(new LambdaUpdateWrapper<KnowledgePoint>()
                .set(KnowledgePoint::getDeletedAt, NOW)
                .set(KnowledgePoint::getMergedInto, mergedInto)
                .eq(KnowledgePoint::getId, kp.getId()));
    }

    /** 知识库中改类型（#25）：改成只收录的类型时 card_state 保留 */
    private void changeType(KnowledgePoint kp, KnowledgePointType type) {
        knowledgePointMapper.update(new LambdaUpdateWrapper<KnowledgePoint>()
                .set(KnowledgePoint::getType, type)
                .eq(KnowledgePoint::getId, kp.getId()));
    }

    @Test
    void 删除的_被合并的_改成只收录的不进今天的计划和接下来7天() {
        KnowledgePoint normalReview = reviewedKp("正常的复习", 1, NOW);
        KnowledgePoint normalNew = newKp("正常的新知识", 2);
        reviewedKp("正常的明天到期", 3, DAY_END);
        delete(reviewedKp("删除的复习", 4, NOW), null);
        delete(newKp("删除的新知识", 5), null);
        delete(reviewedKp("删除的明天到期", 6, DAY_END), null);
        delete(reviewedKp("被合并的复习", 7, NOW), normalReview.getId());
        delete(reviewedKp("被合并的明天到期", 8, DAY_END), normalNew.getId());
        changeType(reviewedKp("改成个人经历的复习", 9, NOW), KnowledgePointType.EXPERIENCE);
        changeType(newKp("改成系统设计的新知识", 10), KnowledgePointType.SYSTEM_DESIGN);
        changeType(reviewedKp("改成系统设计的明天到期", 11, DAY_END), KnowledgePointType.SYSTEM_DESIGN);

        TodayPlan plan = planService.today(1);

        assertThat(titles(plan.reviews())).containsExactly("正常的复习");
        assertThat(titles(plan.newItems())).containsExactly("正常的新知识");
        assertThat(counts(plan)).containsExactly(1, 0, 0, 0, 0, 0, 0);
        // 知识点总数不算删除和被合并的，算只收录的
        assertThat(plan.knowledgePointCount()).isEqualTo(6);
    }

    @Test
    void 今天学过的新知识删除后不再显示_但仍占用今天的名额() {
        KnowledgePoint learned = newKp("学完后删除的", 1);
        firstReview(learned, NOW.minus(Duration.ofHours(1)));
        newKp("新知识一", 2);
        newKp("新知识二", 3);
        newKp("新知识三", 4);
        delete(learned, null);

        TodayPlan plan = planService.today(1);

        assertThat(plan.completed()).isEmpty();
        // 学过是已经发生的事，删掉不会退回名额：每天新学 3 个，今天已经学了 1 个
        assertThat(plan.newLearnedToday()).isEqualTo(1);
        assertThat(titles(plan.newItems())).containsExactly("新知识一", "新知识二");
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

    private void session(long userId, Instant startedAt, int activeSeconds) {
        StudySession session = new StudySession();
        session.setUserId(userId);
        session.setType(SessionType.STUDY);
        session.setStartedAt(startedAt);
        session.setSkippedExplanation(false);
        session.setActiveSeconds(activeSeconds);
        sessionMapper.insert(session);
    }

    @Test
    void 今天已学的分钟数来自今天开始的学习会话() {
        session(1, DAY_START, 600);
        session(1, NOW, 150);
        // 昨天开始的会话整个算在昨天，其他用户的不算
        session(1, DAY_START.minusSeconds(1), 1200);
        session(2, NOW, 3000);

        // 750 秒，不足一分钟的舍去
        assertThat(planService.today(1).studiedMinutesToday()).isEqualTo(12);
    }

    @Test
    void 学完今天的全部任务后记一条当天完成() {
        KnowledgePoint review = reviewedKp("到期复习", 1, NOW);
        KnowledgePoint fresh = newKp("新知识", 2);
        // 还有没学完的：不记
        planService.today(1);
        assertThat(completionMapper.selectCount(null)).isZero();

        firstReview(fresh, NOW);
        log(review, NOW, Rating.GOOD, false);
        cardStateMapper.updateById(CardState.of(review.getId(), 1,
                new Card(State.REVIEW, null, 8.0, 5.0, NOW.plus(Duration.ofDays(8)), NOW)));
        planService.today(1);
        // 再打开一次也只有一条
        planService.today(1);

        assertThat(completionMapper.selectList(null)).singleElement().satisfies(c -> {
            assertThat(c.getUserId()).isEqualTo(1);
            assertThat(c.getStudyDate()).isEqualTo(LocalDate.of(2026, 10, 29));
            assertThat(c.getCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 今天本来就没有任务时不记当天完成() {
        reviewedKp("很多天后才到期", 1, NOW.plus(Duration.ofDays(20)));

        TodayPlan plan = planService.today(1);

        assertThat(plan.reviews()).isEmpty();
        assertThat(plan.newItems()).isEmpty();
        assertThat(completionMapper.selectCount(null)).isZero();
    }

    private void completion(long userId, LocalDate date) {
        StudyDayCompletion completion = new StudyDayCompletion();
        completion.setUserId(userId);
        completion.setStudyDate(date);
        completion.setCompletedAt(NOW);
        completionMapper.insert(completion);
    }

    /** 一条带下次到期时间的评分流水，连续天数靠它推算过去有没有到期的复习。 */
    private void reviewDue(KnowledgePoint kp, Instant reviewedAt, Instant dueAfter) {
        ReviewLog log = new ReviewLog();
        log.setUserId(kp.getUserId());
        log.setKpId(kp.getId());
        log.setRatingUser(Rating.GOOD);
        log.setStateBefore(State.REVIEW);
        log.setFirstReview(false);
        log.setReviewedAt(reviewedAt);
        log.setDueAfter(dueAfter);
        reviewLogMapper.insert(log);
    }

    private static final LocalDate OCT_26 = LocalDate.of(2026, 10, 26);
    private static final LocalDate OCT_28 = LocalDate.of(2026, 10, 28);
    /** 10 月 27 日这个学习日内的时刻（学习日是 [10-26T20:00Z, 10-27T20:00Z)） */
    private static final Instant IN_OCT_27 = Instant.parse("2026-10-27T02:00:00Z");
    private static final Instant BEFORE_OCT_27 = Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void 连续天数_没有完成记录时为0() {
        assertThat(planService.today(1).currentStreak()).isZero();
    }

    @Test
    void 连续天数_今天完成后加上今天() {
        completion(1, OCT_28);
        firstReview(newKp("新知识", 1), NOW);

        assertThat(planService.today(1).currentStreak()).isEqualTo(2);
    }

    @Test
    void 连续天数_今天还没完成时算到昨天_今天有到期复习也不打断() {
        completion(1, OCT_28);
        reviewedKp("今天到期", 1, NOW);

        assertThat(planService.today(1).currentStreak()).isEqualTo(1);
    }

    @Test
    void 连续天数_没有记录但当天有到期复习时打断() {
        completion(1, OCT_26);
        completion(1, OCT_28);
        reviewDue(kp(1, "漏掉的复习", 1, KnowledgePointStatus.ACTIVE), BEFORE_OCT_27, IN_OCT_27);

        assertThat(planService.today(1).currentStreak()).isEqualTo(1);
    }

    @Test
    void 连续天数_没有记录也没有到期复习时不计入也不打断() {
        completion(1, OCT_26);
        completion(1, OCT_28);
        reviewDue(kp(1, "很久以后才到期", 1, KnowledgePointStatus.ACTIVE), BEFORE_OCT_27, NOW.plus(Duration.ofDays(20)));

        assertThat(planService.today(1).currentStreak()).isEqualTo(2);
    }

    @Test
    void 连续天数_到期的复习当天做了就不算漏掉() {
        completion(1, OCT_26);
        completion(1, OCT_28);
        KnowledgePoint kp = kp(1, "当天复习了", 1, KnowledgePointStatus.ACTIVE);
        reviewDue(kp, BEFORE_OCT_27, IN_OCT_27);
        reviewDue(kp, IN_OCT_27, NOW.plus(Duration.ofDays(20)));

        assertThat(planService.today(1).currentStreak()).isEqualTo(2);
    }

    @Test
    void 连续天数_暂停的知识点不打断() {
        completion(1, OCT_26);
        completion(1, OCT_28);
        reviewDue(kp(1, "暂停了", 1, KnowledgePointStatus.SUSPENDED), BEFORE_OCT_27, IN_OCT_27);

        assertThat(planService.today(1).currentStreak()).isEqualTo(2);
    }

    @Test
    void 连续天数_其他用户的记录和复习都不算() {
        completion(1, OCT_26);
        completion(1, OCT_28);
        completion(2, LocalDate.of(2026, 10, 27));
        reviewDue(kp(2, "别人漏掉的", 1, KnowledgePointStatus.ACTIVE), BEFORE_OCT_27, IN_OCT_27);

        assertThat(planService.today(1).currentStreak()).isEqualTo(2);
    }

    @Test
    void 本周时长从周一的学习日开始算() {
        // 10 月 29 日是周四，周一的学习日从 10-25T20:00Z 开始
        Instant mondayStart = Instant.parse("2026-10-25T20:00:00Z");
        session(1, mondayStart, 600);
        session(1, NOW, 150);
        // 上周日的最后一秒、其他用户的都不算
        session(1, mondayStart.minusSeconds(1), 1200);
        session(2, NOW, 3000);

        // 750 秒，不足一分钟的舍去
        assertThat(planService.today(1).studiedMinutesThisWeek()).isEqualTo(12);
    }

    @Test
    void 周一凌晨分界之前仍然算上一周() {
        // 北京时间 10 月 26 日（周一）03:30，学习日还是 10 月 25 日（周日），本周从 10 月 19 日的学习日开始
        setClock(Instant.parse("2026-10-25T19:30:00Z"));
        session(1, Instant.parse("2026-10-18T20:00:00Z"), 600);

        assertThat(planService.today(1).studiedMinutesThisWeek()).isEqualTo(10);
    }
}
