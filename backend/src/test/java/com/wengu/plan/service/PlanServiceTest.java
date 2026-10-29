package com.wengu.plan.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.plan.dto.PlanItem;
import com.wengu.plan.dto.TodayPlan;
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
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
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
    }
}
