package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointDetail;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.LibraryItem;
import com.wengu.knowledge.dto.LibrarySort;
import com.wengu.knowledge.dto.LibraryView;
import com.wengu.knowledge.dto.Mastery;
import com.wengu.knowledge.dto.NextReview;
import com.wengu.knowledge.dto.SortDirection;
import com.wengu.knowledge.dto.SourceRef;
import com.wengu.knowledge.dto.StudyRecord;
import com.wengu.knowledge.dto.UpdateKnowledgePointRequest;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.entity.ItemDecision;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.source.entity.Source;
import com.wengu.source.entity.SourceType;
import com.wengu.source.mapper.SourceMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * 知识库列表和详情（#50，决策 030）。真实 MySQL。
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
    private ExtractionDraftMapper draftMapper;

    @Autowired
    private ExtractionDraftItemMapper draftItemMapper;

    @Autowired
    private SourceMapper sourceMapper;

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
        draftItemMapper.delete(null);
        draftMapper.delete(null);
        sourceMapper.delete(null);

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

    // ---------- 详情 ----------

    private long source(String name) {
        Source source = new Source();
        source.setUserId(USER_ID);
        source.setType(SourceType.QUESTION);
        source.setName(name);
        source.setContent("原文");
        source.setCreatedAt(NOW);
        sourceMapper.insert(source);
        return source.getId();
    }

    /** 从这份材料抽取的一个草稿条目，返回条目 id */
    private long draftItem(long sourceId, String summary, String questionIds) {
        ExtractionDraft draft = new ExtractionDraft();
        draft.setUserId(USER_ID);
        draft.setSourceId(sourceId);
        draft.setCreatedAt(NOW);
        draft.setConfirmedAt(NOW);
        draftMapper.insert(draft);
        ExtractionDraftItem item = new ExtractionDraftItem();
        item.setUserId(USER_ID);
        item.setDraftId(draft.getId());
        item.setBatchId(0L);
        item.setItemOrder(1);
        item.setTitle("AI 写的标题");
        item.setSummary(summary);
        item.setType(KnowledgePointType.CONCEPT);
        item.setSourceQuestionIds(questionIds);
        item.setDecision(ItemDecision.ACCEPTED);
        draftItemMapper.insert(item);
        return item.getId();
    }

    /** 像确认草稿那样建一个知识点：关联材料和草稿条目 */
    private long fromDraft(long sourceId, String title, String summary, String questionIds) {
        long itemId = draftItem(sourceId, summary, questionIds);
        return knowledgePointService.create(USER_ID, sourceId, List.of(new KnowledgePointService.NewKnowledgePoint(
                KnowledgePointType.CONCEPT, title, summary.strip(), itemId))).getFirst();
    }

    private void log(long kpId, boolean firstReview, Rating rating, Rating ratingAi, Instant reviewedAt) {
        ReviewLog log = new ReviewLog();
        log.setUserId(USER_ID);
        log.setKpId(kpId);
        log.setRatingUser(rating);
        log.setRatingAi(ratingAi);
        log.setStateBefore(State.LEARNING);
        log.setFirstReview(firstReview);
        log.setReviewedAt(reviewedAt);
        reviewLogMapper.insert(log);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 详情中的掌握程度和下次复习与列表一致() {
        KnowledgePointDetail detail = libraryService.detail(USER_ID, fair);

        assertThat(detail.title()).isEqualTo("HashMap 是怎么扩容的？");
        assertThat(detail.type()).isEqualTo(KnowledgePointType.MECHANISM);
        assertThat(detail.status()).isEqualTo(KnowledgePointStatus.ACTIVE);
        assertThat(detail.mastery()).isEqualTo(Mastery.FAIR);
        assertThat(detail.nextReview()).isEqualTo(new NextReview(LocalDate.of(2026, 10, 30), 1));
        // 不是从草稿来的：没有 AI 原文，也就谈不上改过
        assertThat(detail.summary()).isEqualTo("要点");
        assertThat(detail.aiSummary()).isNull();
        assertThat(detail.summaryEdited()).isFalse();
        assertThat(detail.reviews()).isEmpty();
        assertThat(detail.sources()).isEmpty();

        KnowledgePointDetail paused = libraryService.detail(USER_ID, suspended);
        assertThat(paused.status()).isEqualTo(KnowledgePointStatus.SUSPENDED);
        assertThat(paused.mastery()).isEqualTo(Mastery.SUSPENDED);
        assertThat(paused.nextReview()).isNull();
    }

    @Test
    void 要点和AI原文对比_恢复后不算改过() {
        long sourceId = source("Java 面试题");
        long kpId = fromDraft(sourceId, "什么是 JMM？", "AI 写的要点\n", "1");

        KnowledgePointDetail original = libraryService.detail(USER_ID, kpId);
        assertThat(original.aiSummary()).isEqualTo("AI 写的要点\n");
        // 首尾空白不算改动：保存时会去掉
        assertThat(original.summaryEdited()).isFalse();

        knowledgePointService.update(USER_ID, kpId, new UpdateKnowledgePointRequest(null, null, "我改过的要点"));
        KnowledgePointDetail edited = libraryService.detail(USER_ID, kpId);
        assertThat(edited.summary()).isEqualTo("我改过的要点");
        assertThat(edited.aiSummary()).isEqualTo("AI 写的要点\n");
        assertThat(edited.summaryEdited()).isTrue();

        // 恢复 AI 版本：前端把原文填回去，走普通的编辑
        knowledgePointService.update(USER_ID, kpId, new UpdateKnowledgePointRequest(null, null, edited.aiSummary()));
        assertThat(libraryService.detail(USER_ID, kpId).summaryEdited()).isFalse();
    }

    @Test
    void 来源带上题号_去重时补关联的材料只有名称() {
        long first = source("Java 面试题");
        long second = source("并发专题");
        long kpId = fromDraft(first, "什么是 JMM？", "要点", "5,1,3");
        knowledgePointService.linkToSource(USER_ID, second, List.of(kpId));

        assertThat(libraryService.detail(USER_ID, kpId).sources()).containsExactly(
                new SourceRef(first, "Java 面试题", List.of(1, 3, 5)),
                new SourceRef(second, "并发专题", List.of()));
    }

    @Test
    void 合并后学习记录和来源一起显示_最近的在前() {
        long first = source("Java 面试题");
        long second = source("并发专题");
        long primary = fromDraft(first, "volatile 保证了什么？", "要点", "1");
        long merged = fromDraft(second, "volatile 有什么用？", "要点", "2,4");
        long earlier = fromDraft(second, "volatile 的可见性是什么？", "要点", "6");
        // 北京时间 10 月 29 日 03:30，还没到 4 点，算 10 月 28 日
        log(primary, true, Rating.GOOD, Rating.GOOD, Instant.parse("2026-10-28T19:30:00Z"));
        log(merged, true, Rating.HARD, null, Instant.parse("2026-10-20T02:00:00Z"));
        log(merged, false, Rating.EASY, Rating.GOOD, Instant.parse("2026-10-25T02:00:00Z"));
        log(earlier, true, Rating.AGAIN, Rating.HARD, Instant.parse("2026-10-10T02:00:00Z"));
        // 先把 earlier 并进 merged，再把 merged 并进 primary：earlier 也改指向 primary
        knowledgePointService.merge(USER_ID, merged, List.of(earlier));
        knowledgePointService.merge(USER_ID, primary, List.of(merged));

        KnowledgePointDetail detail = libraryService.detail(USER_ID, primary);

        assertThat(detail.reviews()).containsExactly(
                new StudyRecord(LocalDate.of(2026, 10, 28), true, Rating.GOOD, Rating.GOOD, null, null),
                new StudyRecord(LocalDate.of(2026, 10, 25), false, Rating.EASY, Rating.GOOD, "volatile 有什么用？", null),
                new StudyRecord(LocalDate.of(2026, 10, 20), true, Rating.HARD, null, "volatile 有什么用？", null),
                new StudyRecord(LocalDate.of(2026, 10, 10), true, Rating.AGAIN, Rating.HARD, "volatile 的可见性是什么？", null));
        assertThat(detail.sources()).containsExactly(
                new SourceRef(first, "Java 面试题", List.of(1)),
                new SourceRef(second, "并发专题", List.of(2, 4, 6)));
        // 主知识点的复习进度不变：它自己的卡
        assertThat(detail.mastery()).isEqualTo(Mastery.NEW);
    }

    @Test
    void 学习记录带上那次学习的会话() {
        long kpId = knowledgePointService.create(USER_ID, null, List.of(point(KnowledgePointType.CONCEPT, "有会话的")))
                .getFirst();
        ReviewLog log = new ReviewLog();
        log.setUserId(USER_ID);
        log.setKpId(kpId);
        log.setSessionId(777L);
        log.setRatingUser(Rating.GOOD);
        log.setStateBefore(State.LEARNING);
        log.setFirstReview(true);
        log.setReviewedAt(Instant.parse("2026-10-20T02:00:00Z"));
        reviewLogMapper.insert(log);

        assertThat(libraryService.detail(USER_ID, kpId).reviews()).extracting(StudyRecord::sessionId).containsExactly(777L);
    }

    @Test
    void 删除的_被合并的_别人的_不存在的都是404() {
        long merged = knowledgePointService.create(USER_ID, null, List.of(point(KnowledgePointType.CONCEPT, "被合并的")))
                .getFirst();
        knowledgePointService.merge(USER_ID, solid, List.of(merged));
        knowledgePointService.delete(USER_ID, List.of(familiar));

        assertNotFound(() -> libraryService.detail(USER_ID, familiar));
        assertNotFound(() -> libraryService.detail(USER_ID, merged));
        assertNotFound(() -> libraryService.detail(OTHER_USER_ID, solid));
        assertNotFound(() -> libraryService.detail(USER_ID, Long.MAX_VALUE));
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
