package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.LibraryItem;
import com.wengu.knowledge.dto.LibrarySort;
import com.wengu.knowledge.dto.LibraryView;
import com.wengu.knowledge.dto.Mastery;
import com.wengu.knowledge.dto.NextReview;
import com.wengu.knowledge.dto.SortDirection;
import com.wengu.knowledge.dto.UpdateKnowledgePointRequest;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.entity.CardState;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 知识库列表（#50，决策 030）。真实 MySQL。
 * 设置为 Asia/Shanghai、4 点分界；现在是北京时间 10 月 29 日 10:00，学习日是 10 月 29 日。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KnowledgeLibraryServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Autowired
    private KnowledgeLibraryService libraryService;

    @Autowired
    private KnowledgePointService knowledgePointService;

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

    /*
     * 按学习顺序建好的知识点（括号中是掌握程度和下次复习）：
     * systemDesign（只收录）、solid（稳定性 30，3 天后）、fair（稳定性 3，明天凌晨 3:59，仍是明天这个学习日）、
     * newOne（待学）、familiar（稳定性 10，20 天后）、shaky（稳定性 1，两天前就到期了）、
     * suspended（暂停，稳定性 50）、newTwo（待学）；另有一个删除的、一个其他用户的。
     */
    private long systemDesign;
    private long solid;
    private long fair;
    private long newOne;
    private long familiar;
    private long shaky;
    private long suspended;
    private long newTwo;

    @BeforeEach
    void setUp() {
        // 空库上 V7 删掉了默认用户和它的设置（决策 017），用户 1 没有设置时先插入一行
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        settingsService.update(USER_ID, new UpdateSettingsRequest("Asia/Shanghai", 4, 3));
        reviewLogMapper.delete(null);
        cardStateMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);

        List<Long> ids = knowledgePointService.create(USER_ID, null, List.of(
                point(KnowledgePointType.SYSTEM_DESIGN, "如何设计秒杀？"),
                point(KnowledgePointType.CONCEPT, "什么是 volatile？"),
                point(KnowledgePointType.MECHANISM, "HashMap 是怎么扩容的？"),
                point(KnowledgePointType.CONCEPT, "什么是 CAS？"),
                point(KnowledgePointType.COMPARISON, "synchronized 和 ReentrantLock 有什么区别？"),
                point(KnowledgePointType.CONCEPT, "什么是 AQS？"),
                point(KnowledgePointType.CONCEPT, "什么是 ThreadLocal？"),
                point(KnowledgePointType.ENUMERATION, "线程池有哪些拒绝策略？"),
                point(KnowledgePointType.CONCEPT, "删除的知识点")));
        systemDesign = ids.get(0);
        solid = ids.get(1);
        fair = ids.get(2);
        newOne = ids.get(3);
        familiar = ids.get(4);
        shaky = ids.get(5);
        suspended = ids.get(6);
        newTwo = ids.get(7);

        reviewed(solid, 30, NOW.plus(Duration.ofDays(3)));
        reviewed(fair, 3, Instant.parse("2026-10-30T19:59:00Z"));
        reviewed(familiar, 10, NOW.plus(Duration.ofDays(20)));
        reviewed(shaky, 1, NOW.minus(Duration.ofDays(2)));
        reviewed(suspended, 50, NOW.plus(Duration.ofDays(40)));
        knowledgePointService.suspend(USER_ID, List.of(suspended));
        knowledgePointService.delete(USER_ID, List.of(ids.get(8)));
        knowledgePointService.create(OTHER_USER_ID, null, List.of(point(KnowledgePointType.CONCEPT, "别人的")));
    }

    private static KnowledgePointService.NewKnowledgePoint point(KnowledgePointType type, String title) {
        return new KnowledgePointService.NewKnowledgePoint(type, title, "要点", null);
    }

    /** 把新卡改成复习过的 */
    private void reviewed(long kpId, double stability, Instant due) {
        cardStateMapper.update(new LambdaUpdateWrapper<CardState>()
                .eq(CardState::getKpId, kpId)
                .set(CardState::getState, State.REVIEW)
                .set(CardState::getStep, null)
                .set(CardState::getStability, stability)
                .set(CardState::getDifficulty, 5.0)
                .set(CardState::getDue, due)
                .set(CardState::getLastReview, NOW.minus(Duration.ofDays(1))));
    }

    private LibraryView list(Mastery mastery, KnowledgePointType type) {
        return libraryService.list(USER_ID, mastery, type, LibrarySort.ORDER, SortDirection.ASC);
    }

    private List<Long> ids(LibrarySort sort, SortDirection direction) {
        return libraryService.list(USER_ID, null, null, sort, direction).items().stream()
                .map(LibraryItem::id).toList();
    }

    @Test
    void 默认按学习顺序_不含删除的和其他用户的() {
        LibraryView view = list(null, null);

        assertThat(view.total()).isEqualTo(8);
        assertThat(view.items()).extracting(LibraryItem::id)
                .containsExactly(systemDesign, solid, fair, newOne, familiar, shaky, suspended, newTwo);
        assertThat(view.items()).extracting(LibraryItem::mastery).containsExactly(
                Mastery.RECORD_ONLY, Mastery.SOLID, Mastery.FAIR, Mastery.NEW,
                Mastery.FAMILIAR, Mastery.SHAKY, Mastery.SUSPENDED, Mastery.NEW);
        assertThat(view.counts()).containsExactly(
                entry(Mastery.SOLID, 1), entry(Mastery.FAMILIAR, 1), entry(Mastery.FAIR, 1), entry(Mastery.SHAKY, 1),
                entry(Mastery.NEW, 2), entry(Mastery.SUSPENDED, 1), entry(Mastery.RECORD_ONLY, 1));
    }

    @Test
    void 下次复习按学习日给出_只有学过的才有() {
        Map<Long, LibraryItem> items = list(null, null).items().stream()
                .collect(Collectors.toMap(LibraryItem::id, item -> item));

        assertThat(items.get(solid).nextReview()).isEqualTo(new NextReview(LocalDate.of(2026, 11, 1), 3));
        // 北京时间 10 月 31 日 03:59，还没到 4 点，属于 10 月 30 日这个学习日，也就是明天
        assertThat(items.get(fair).nextReview()).isEqualTo(new NextReview(LocalDate.of(2026, 10, 30), 1));
        // 已经过期的：日期照实给出，天数为负，它在今天的计划里
        assertThat(items.get(shaky).nextReview()).isEqualTo(new NextReview(LocalDate.of(2026, 10, 27), -2));
        // 待学、暂停（虽然有到期时间）、只收录都没有下次复习
        for (long id : List.of(newOne, newTwo, suspended, systemDesign)) {
            assertThat(items.get(id).nextReview()).as("知识点 %d", id).isNull();
        }
    }

    @Test
    void 类型筛选改变数量_掌握程度筛选不改变数量() {
        Map<Mastery, Integer> conceptCounts = Map.of(
                Mastery.SOLID, 1, Mastery.FAMILIAR, 0, Mastery.FAIR, 0, Mastery.SHAKY, 1,
                Mastery.NEW, 1, Mastery.SUSPENDED, 1, Mastery.RECORD_ONLY, 0);

        LibraryView concepts = list(null, KnowledgePointType.CONCEPT);
        assertThat(concepts.items()).extracting(LibraryItem::id).containsExactly(solid, newOne, shaky, suspended);
        assertThat(concepts.counts()).containsExactlyInAnyOrderEntriesOf(conceptCounts);
        assertThat(concepts.total()).isEqualTo(8);

        LibraryView newConcepts = list(Mastery.NEW, KnowledgePointType.CONCEPT);
        assertThat(newConcepts.items()).extracting(LibraryItem::id).containsExactly(newOne);
        assertThat(newConcepts.counts()).containsExactlyInAnyOrderEntriesOf(conceptCounts);

        LibraryView none = list(Mastery.FAIR, KnowledgePointType.CONCEPT);
        assertThat(none.items()).isEmpty();
        assertThat(none.counts()).containsExactlyInAnyOrderEntriesOf(conceptCounts);
    }

    @Test
    void 只按掌握程度筛选() {
        LibraryView newOnes = list(Mastery.NEW, null);

        assertThat(newOnes.items()).extracting(LibraryItem::id).containsExactly(newOne, newTwo);
        assertThat(newOnes.counts().get(Mastery.FAIR)).isEqualTo(1);
        assertThat(list(Mastery.RECORD_ONLY, null).items()).extracting(LibraryItem::id).containsExactly(systemDesign);
    }

    @Test
    void 按掌握程度排序_待学暂停只收录总在最后() {
        assertThat(ids(LibrarySort.MASTERY, SortDirection.ASC))
                .containsExactly(shaky, fair, familiar, solid, newOne, newTwo, suspended, systemDesign);
        assertThat(ids(LibrarySort.MASTERY, SortDirection.DESC))
                .containsExactly(solid, familiar, fair, shaky, newOne, newTwo, suspended, systemDesign);
    }

    @Test
    void 按下次复习排序_待学暂停只收录总在最后() {
        assertThat(ids(LibrarySort.NEXT_REVIEW, SortDirection.ASC))
                .containsExactly(shaky, fair, solid, familiar, newOne, newTwo, suspended, systemDesign);
        assertThat(ids(LibrarySort.NEXT_REVIEW, SortDirection.DESC))
                .containsExactly(familiar, solid, fair, shaky, newOne, newTwo, suspended, systemDesign);
    }

    @Test
    void 学习顺序不分方向() {
        assertThat(ids(LibrarySort.ORDER, SortDirection.DESC))
                .containsExactly(systemDesign, solid, fair, newOne, familiar, shaky, suspended, newTwo);
    }

    @Test
    void 筛选和排序同时生效() {
        LibraryView view = libraryService.list(USER_ID, null, KnowledgePointType.CONCEPT,
                LibrarySort.MASTERY, SortDirection.DESC);

        assertThat(view.items()).extracting(LibraryItem::id).containsExactly(solid, shaky, newOne, suspended);
    }

    @Test
    void 改成只收录类型的知识点保留了调度状态_仍算只收录() {
        knowledgePointService.update(USER_ID, solid,
                new UpdateKnowledgePointRequest(null, KnowledgePointType.EXPERIENCE, null));

        LibraryItem item = list(null, null).items().stream().filter(i -> i.id() == solid).findFirst().orElseThrow();
        assertThat(item.mastery()).isEqualTo(Mastery.RECORD_ONLY);
        assertThat(item.nextReview()).isNull();
    }

    @Test
    void 没有知识点时数量都是零() {
        cardStateMapper.delete(null);
        knowledgePointMapper.delete(null);

        LibraryView view = list(null, null);

        assertThat(view.total()).isZero();
        assertThat(view.items()).isEmpty();
        assertThat(view.counts()).hasSize(Mastery.values().length).allSatisfy((m, n) -> assertThat(n).isZero());
    }
}
