package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.ConfirmDraftRequest;
import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ItemDecision;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.entity.KpSource;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.service.ReviewService;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.service.QuestionSplitService;
import com.wengu.source.service.SourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 确认草稿（决策 015）：生成知识点、关联材料、初始化调度状态、记录取消的条目，以及事务回滚。
 * 用真实的 MySQL；切分和抽取的模型调用换成模拟对象，线程池换成 {@link ManualTaskExecutor}。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DraftConfirmTest {

    private static final String CONTENT = """
            1. 什么是 MVCC？
            2. 如何设计一个秒杀系统？
            3. 你在项目中是怎么使用缓存的？
            4. TCP 和 UDP 有什么区别？
            """;

    @Autowired
    private ExtractionDraftService draftService;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private ExtractionDraftMapper draftMapper;

    @Autowired
    private ExtractionBatchMapper batchMapper;

    @Autowired
    private ExtractionDraftItemMapper itemMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private KpSourceMapper kpSourceMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @MockitoBean
    private QuestionSplitService splitService;

    @MockitoBean
    private KnowledgeExtractionService extractionService;

    @MockitoSpyBean
    private ReviewService reviewService;

    @TestBean(name = "extractionExecutor")
    private TaskExecutor testExecutor;

    private long sourceId;

    static TaskExecutor testExecutor() {
        return new ManualTaskExecutor();
    }

    @BeforeEach
    void importSource() {
        ((ManualTaskExecutor) testExecutor).reset();
        cardStateMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);
        itemMapper.delete(null);
        batchMapper.delete(null);
        draftMapper.delete(null);
        when(splitService.split(anyString())).thenReturn(new LlmResult<>(new SplitResult(List.of(
                new SplitQuestion("什么是 MVCC？", List.of()),
                new SplitQuestion("如何设计一个秒杀系统？", List.of()),
                new SplitQuestion("你在项目中是怎么使用缓存的？", List.of()),
                new SplitQuestion("TCP 和 UDP 有什么区别？", List.of()))), "{...}", "test-model", "stop", 10, 10));
        sourceId = sourceService.importQuestions(new ImportQuestionsRequest("后端", CONTENT)).id();
    }

    private static ExtractedKnowledgePoint kp(int order, String title, KnowledgePointType type, Integer... ids) {
        return new ExtractedKnowledgePoint(order, title, "要点 " + order, type, List.of(ids));
    }

    private static final List<ExtractedKnowledgePoint> FIVE_TYPES = List.of(
            kp(1, "MVCC 是怎么实现的？", KnowledgePointType.MECHANISM, 1),
            kp(2, "什么是 ReadView？", KnowledgePointType.CONCEPT, 1),
            kp(3, "如何设计一个秒杀系统？", KnowledgePointType.SYSTEM_DESIGN, 2),
            kp(4, "你在项目中是怎么使用缓存的？", KnowledgePointType.EXPERIENCE, 3),
            kp(5, "TCP 和 UDP 有什么区别？", KnowledgePointType.COMPARISON, 4));

    private static LlmResult<ExtractionResult> result(List<CoveredQuestion> covered, List<ExtractedKnowledgePoint> kps) {
        return new LlmResult<>(new ExtractionResult(List.of(), covered, kps), "raw", "test-model", "stop", 1000, 500);
    }

    /** 抽取成功、等待确认的草稿。 */
    private DraftView readyDraft(LlmResult<ExtractionResult> extraction) {
        when(extractionService.extract(anyList(), any())).thenReturn(extraction);
        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));
        ((ManualTaskExecutor) testExecutor).runAll();
        DraftView ready = draftService.get(view.id());
        assertThat(ready.status()).isEqualTo(DraftStatus.READY);
        return ready;
    }

    private KnowledgePoint existing(long userId, String title, int sortOrder) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(KnowledgePointType.CONCEPT);
        kp.setTitle(title);
        kp.setSummary("要点");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(sortOrder);
        kp.setCreatedAt(Instant.now());
        kp.setUpdatedAt(Instant.now());
        knowledgePointMapper.insert(kp);
        return kp;
    }

    /** 这份草稿生成的知识点，按 sort_order 排列。 */
    private List<KnowledgePoint> created(DraftView draft) {
        List<Long> itemIds = draft.items().stream().map(DraftItemView::id).toList();
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .in(KnowledgePoint::getDraftItemId, itemIds)
                .orderByAsc(KnowledgePoint::getSortOrder));
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 勾选的条目按顺序生成知识点并接在已有知识点之后() {
        existing(1, "已有的知识点？", 5);
        existing(2, "其他用户的知识点？", 99);
        DraftView draft = readyDraft(result(List.of(), FIVE_TYPES));
        long rejectedId = draft.items().get(1).id();

        DraftView confirmed = draftService.confirm(draft.id(), new ConfirmDraftRequest(List.of(rejectedId)));

        assertThat(confirmed.status()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(confirmed.items()).extracting(DraftItemView::decision).containsExactly(ItemDecision.ACCEPTED,
                ItemDecision.REJECTED, ItemDecision.ACCEPTED, ItemDecision.ACCEPTED, ItemDecision.ACCEPTED);

        List<KnowledgePoint> kps = created(confirmed);
        // 取消的条目没有生成知识点；sort_order 接在当前用户的最大值 5 后面，不受其他用户影响
        assertThat(kps).extracting(KnowledgePoint::getTitle).containsExactly(
                "MVCC 是怎么实现的？", "如何设计一个秒杀系统？", "你在项目中是怎么使用缓存的？", "TCP 和 UDP 有什么区别？");
        assertThat(kps).extracting(KnowledgePoint::getSortOrder).containsExactly(6, 7, 8, 9);
        assertThat(kps).extracting(KnowledgePoint::getDraftItemId).doesNotContain(rejectedId);
        assertThat(kps.getFirst().getDraftItemId()).isEqualTo(draft.items().getFirst().id());
        assertThat(kps.getFirst().getSummary()).isEqualTo("要点 1");
        assertThat(kps).allSatisfy(kp -> {
            assertThat(kp.getUserId()).isEqualTo(1L);
            assertThat(kp.getStatus()).isEqualTo(KnowledgePointStatus.ACTIVE);
        });

        // 每个新知识点都关联到这份材料
        List<KpSource> links = kpSourceMapper.selectList(null);
        assertThat(links).extracting(KpSource::getKpId)
                .containsExactlyInAnyOrderElementsOf(kps.stream().map(KnowledgePoint::getId).toList());
        assertThat(links).allSatisfy(link -> assertThat(link.getSourceId()).isEqualTo(sourceId));
        assertThat(draftMapper.selectById(draft.id()).getConfirmedAt()).isNotNull();
    }

    @Test
    void SYSTEM_DESIGN和EXPERIENCE不初始化调度状态() {
        DraftView draft = readyDraft(result(List.of(), FIVE_TYPES));

        DraftView confirmed = draftService.confirm(draft.id(), null);

        List<KnowledgePoint> kps = created(confirmed);
        List<Long> scheduled = kps.stream()
                .filter(kp -> kp.getType() != KnowledgePointType.SYSTEM_DESIGN && kp.getType() != KnowledgePointType.EXPERIENCE)
                .map(KnowledgePoint::getId)
                .toList();
        List<CardState> cards = cardStateMapper.selectList(null);
        assertThat(scheduled).hasSize(3);
        assertThat(cards).extracting(CardState::getKpId).containsExactlyInAnyOrderElementsOf(scheduled);
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.getState()).isEqualTo(State.LEARNING);
            assertThat(card.getLastReview()).isNull();
            assertThat(card.getUserId()).isEqualTo(1L);
        });
    }

    @Test
    void covered引用的已有知识点关联到这份材料且不重复关联() {
        KnowledgePoint tcp = existing(1, "TCP 和 UDP 的区别是什么？", 1);
        KnowledgePoint udp = existing(1, "UDP 有哪些使用场景？", 2);
        kpSourceMapper.insert(KpSource.of(tcp.getId(), sourceId + 100, 1));
        kpSourceMapper.insert(KpSource.of(udp.getId(), sourceId, 1));
        // 第 4 题被已有的 E1（tcp）和 E2（udp）覆盖，不再生成新知识点
        DraftView draft = readyDraft(result(List.of(new CoveredQuestion(4, List.of("E1", "E2"), "同一内容")),
                FIVE_TYPES.subList(0, 4)));

        draftService.confirm(draft.id(), null);

        List<KpSource> tcpLinks = kpSourceMapper.selectList(new LambdaQueryWrapper<KpSource>()
                .eq(KpSource::getKpId, tcp.getId()));
        assertThat(tcpLinks).extracting(KpSource::getSourceId).containsExactlyInAnyOrder(sourceId + 100, sourceId);
        assertThat(kpSourceMapper.selectCount(new LambdaQueryWrapper<KpSource>()
                .eq(KpSource::getKpId, udp.getId()))).isEqualTo(1);
    }

    @Test
    void 任何一步失败时整体回滚() {
        DraftView draft = readyDraft(result(List.of(), FIVE_TYPES));
        doThrow(new IllegalStateException("初始化调度状态失败")).when(reviewService).initCards(anyLong(), anyList());

        assertThatThrownBy(() -> draftService.confirm(draft.id(), new ConfirmDraftRequest(List.of(draft.items().get(1).id()))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(knowledgePointMapper.selectCount(null)).isZero();
        assertThat(kpSourceMapper.selectCount(null)).isZero();
        assertThat(cardStateMapper.selectCount(null)).isZero();
        ExtractionDraft stored = draftMapper.selectById(draft.id());
        assertThat(stored.getConfirmedAt()).isNull();
        DraftView after = draftService.get(draft.id());
        assertThat(after.status()).isEqualTo(DraftStatus.READY);
        assertThat(after.items()).extracting(DraftItemView::decision).containsOnlyNulls();
    }

    @Test
    void 全部取消也能确认() {
        DraftView draft = readyDraft(result(List.of(), FIVE_TYPES));

        DraftView confirmed = draftService.confirm(draft.id(),
                new ConfirmDraftRequest(draft.items().stream().map(DraftItemView::id).toList()));

        assertThat(confirmed.status()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(confirmed.items()).extracting(DraftItemView::decision).containsOnly(ItemDecision.REJECTED);
        assertThat(knowledgePointMapper.selectCount(null)).isZero();
        assertThat(cardStateMapper.selectCount(null)).isZero();
    }

    @Test
    void 未抽取完成或失败的草稿不能确认() {
        when(extractionService.extract(anyList(), any())).thenThrow(new IllegalStateException("Key 不对"));
        DraftView running = draftService.create(new CreateDraftRequest(sourceId, null));
        assertError(() -> draftService.confirm(running.id(), null), ErrorCode.CONFLICT);

        ((ManualTaskExecutor) testExecutor).runAll();
        assertThat(draftService.get(running.id()).status()).isEqualTo(DraftStatus.FAILED);
        assertError(() -> draftService.confirm(running.id(), null), ErrorCode.CONFLICT);
        assertThat(knowledgePointMapper.selectCount(null)).isZero();
    }

    @Test
    void 不能重复确认也不能再重试且取消的条目必须属于这份草稿() {
        DraftView draft = readyDraft(result(List.of(), FIVE_TYPES));

        assertError(() -> draftService.confirm(draft.id(), new ConfirmDraftRequest(List.of(Long.MAX_VALUE))),
                ErrorCode.BAD_REQUEST);
        assertThat(knowledgePointMapper.selectCount(null)).isZero();

        draftService.confirm(draft.id(), null);
        assertError(() -> draftService.confirm(draft.id(), null), ErrorCode.CONFLICT);
        assertError(() -> draftService.retry(draft.id()), ErrorCode.CONFLICT);
        assertThat(knowledgePointMapper.selectCount(null)).isEqualTo(5);
    }
}
