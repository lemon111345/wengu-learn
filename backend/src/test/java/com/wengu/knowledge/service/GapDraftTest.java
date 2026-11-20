package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftSummary;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointDetail;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionDraftItem;
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
import com.wengu.llm.service.LlmException;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.entity.Source;
import com.wengu.source.entity.SourceType;
import com.wengu.source.mapper.SourceMapper;
import com.wengu.source.service.QuestionSplitService;
import com.wengu.source.service.SourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 学习缺口生成知识点（#31，决策 053）：GAP 材料、抽取完成后自动确认并挪到最前面、草稿列表和知识库详情的显示。
 * 用真实的 MySQL；抽取的模型调用换成模拟对象，线程池换成 {@link ManualTaskExecutor}。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GapDraftTest {

    private static final long USER_ID = 1L;

    private static final List<String> GAPS = List.of("代理是什么？为什么要用代理？", "JDK 动态代理和 CGLIB 代理有什么区别？");

    @Autowired
    private ExtractionDraftService draftService;

    @Autowired
    private KnowledgeLibraryService libraryService;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private SourceMapper sourceMapper;

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

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private QuestionSplitService splitService;

    @MockitoBean
    private KnowledgeExtractionService extractionService;

    @TestBean(name = "extractionExecutor")
    private TaskExecutor testExecutor;

    static TaskExecutor testExecutor() {
        return new ManualTaskExecutor();
    }

    @BeforeEach
    void reset() {
        ((ManualTaskExecutor) testExecutor).reset();
        // 空库上 V7 删掉了默认用户的设置（决策 017），知识库详情要按用户的学习日显示日期
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        cardStateMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);
        itemMapper.delete(null);
        batchMapper.delete(null);
        draftMapper.delete(null);
        sourceMapper.delete(null);
    }

    private static LlmResult<ExtractionResult> result(List<CoveredQuestion> covered, ExtractedKnowledgePoint... kps) {
        return new LlmResult<>(new ExtractionResult(List.of(), covered, List.of(kps)), "raw", "test-model", "stop", 100, 50);
    }

    private static ExtractedKnowledgePoint kp(int order, String title, Integer... questionIds) {
        return new ExtractedKnowledgePoint(order, title, "要点 " + order, KnowledgePointType.CONCEPT, List.of(questionIds));
    }

    private KnowledgePoint existing(String title, int sortOrder) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
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

    /** 建缺口草稿，并让后台执行完 */
    private DraftView gapDraft(List<String> gaps) {
        long draftId = draftService.createGapDraft(USER_ID, "Spring 中一个单例 Bean 的创建过程是怎样的？", gaps);
        ((ManualTaskExecutor) testExecutor).runAll();
        return draftService.get(USER_ID, draftId);
    }

    /** 用户的全部知识点，按学习顺序 */
    private List<String> titlesInOrder() {
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getUserId, USER_ID)
                        .orderByAsc(KnowledgePoint::getSortOrder))
                .stream().map(KnowledgePoint::getTitle).toList();
    }

    private long importQuestions(String name) {
        when(splitService.split(anyString())).thenReturn(new LlmResult<>(new SplitResult(List.of(
                new SplitQuestion("什么是 MVCC？", List.of()))), "{...}", "test-model", "stop", 10, 10));
        return sourceService.importQuestions(USER_ID, new ImportQuestionsRequest(name, "1. 什么是 MVCC？")).id();
    }

    @Test
    void 缺口材料的类型_题号_原文_名称() {
        long id = sourceService.createGapSource(USER_ID, "学习缺口：" + "长".repeat(300), GAPS);

        Source source = sourceMapper.selectById(id);
        assertThat(source.getType()).isEqualTo(SourceType.GAP);
        assertThat(source.getName()).hasSize(255).startsWith("学习缺口：");
        assertThat(source.getContent()).isEqualTo(String.join("\n", GAPS));
        assertThat(sourceService.getQuestions(USER_ID, id)).extracting(SourceQuestion::id, SourceQuestion::text)
                .containsExactly(tuple(1, GAPS.get(0)),
                        tuple(2, GAPS.get(1)));
    }

    @Test
    void 导入接口不能建缺口材料() {
        assertThatThrownBy(() -> sourceService.importContent(USER_ID, "缺口", "1. 代理是什么？", SourceType.GAP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 抽取成功后自动确认_全部接受_排在学习顺序最前面() {
        existing("已有的一", 1);
        existing("已有的二", 2);
        when(extractionService.extract(anyList(), any())).thenReturn(result(List.of(),
                kp(1, "什么是代理模式？", 1), kp(2, "JDK 动态代理和 CGLIB 有什么区别？", 2)));

        DraftView draft = gapDraft(GAPS);

        assertThat(draft.status()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(draft.sourceName()).isEqualTo("学习缺口：Spring 中一个单例 Bean 的创建过程是怎样的？");
        assertThat(itemMapper.selectList(null)).extracting(ExtractionDraftItem::getDecision)
                .containsOnly(ItemDecision.ACCEPTED);
        assertThat(titlesInOrder()).containsExactly(
                "什么是代理模式？", "JDK 动态代理和 CGLIB 有什么区别？", "已有的一", "已有的二");
        // 参与调度，进了待学
        assertThat(cardStateMapper.selectCount(null)).isEqualTo(2);
    }

    @Test
    void 后加入的缺口排在更前面() {
        existing("已有的", 1);
        doReturn(result(List.of(), kp(1, "第一次的缺口", 1))).when(extractionService).extract(anyList(), any());
        gapDraft(List.of("第一次的缺口？"));
        doReturn(result(List.of(), kp(1, "第二次的缺口", 1))).when(extractionService).extract(anyList(), any());
        gapDraft(List.of("第二次的缺口？"));

        assertThat(titlesInOrder()).containsExactly("第二次的缺口", "第一次的缺口", "已有的");
    }

    @Test
    void 被已有知识点覆盖的缺口不生成_已有知识点关联到缺口材料() {
        KnowledgePoint proxy = existing("什么是代理模式？", 1);
        // 第 1 条被已有的 E1 覆盖
        when(extractionService.extract(anyList(), any())).thenReturn(result(
                List.of(new CoveredQuestion(1, List.of("E1"), "同一内容")), kp(1, "JDK 动态代理和 CGLIB 有什么区别？", 2)));

        DraftView draft = gapDraft(GAPS);

        assertThat(draft.status()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(draft.covered()).hasSize(1);
        assertThat(titlesInOrder()).containsExactly("JDK 动态代理和 CGLIB 有什么区别？", "什么是代理模式？");
        assertThat(kpSourceMapper.selectList(new LambdaQueryWrapper<KpSource>().eq(KpSource::getKpId, proxy.getId())))
                .extracting(KpSource::getSourceId).containsExactly(draft.sourceId());
    }

    @Test
    void 抽取失败时不确认_重试成功后自动确认() {
        doThrow(new LlmException(ErrorCode.LLM_TIMEOUT)).when(extractionService).extract(anyList(), any());
        DraftView failed = gapDraft(GAPS);
        assertThat(failed.status()).isEqualTo(DraftStatus.FAILED);
        assertThat(knowledgePointMapper.selectCount(null)).isZero();

        doReturn(result(List.of(), kp(1, "什么是代理模式？", 1, 2))).when(extractionService).extract(anyList(), any());
        draftService.retry(USER_ID, failed.id());
        ((ManualTaskExecutor) testExecutor).runAll();

        assertThat(draftService.get(USER_ID, failed.id()).status()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(titlesInOrder()).containsExactly("什么是代理模式？");
    }

    @Test
    void 普通材料抽取完成后不自动确认() {
        long sourceId = importQuestions("后端");
        doReturn(result(List.of(), kp(1, "MVCC 是怎么实现的？", 1))).when(extractionService).extract(anyList(), any());

        DraftView view = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null));
        ((ManualTaskExecutor) testExecutor).runAll();

        assertThat(draftService.get(USER_ID, view.id()).status()).isEqualTo(DraftStatus.READY);
        assertThat(knowledgePointMapper.selectCount(null)).isZero();
    }

    @Test
    void 草稿列表不返回已确认的缺口草稿_失败的照常返回_最近导入不被挤占() {
        // 11 份已确认的导入：只返回最近 10 份。每份抽出的标题不同，否则后面的会和已有知识点重复
        List<Long> imports = IntStream.rangeClosed(1, 11).mapToObj(i -> {
            doReturn(result(List.of(), kp(1, "知识点 " + i, 1))).when(extractionService).extract(anyList(), any());
            DraftView view = draftService.create(USER_ID, new CreateDraftRequest(importQuestions("导入 " + i), null));
            ((ManualTaskExecutor) testExecutor).runAll();
            draftService.confirm(USER_ID, view.id(), null);
            return view.id();
        }).toList();
        // 之后两份缺口草稿：一份确认了，一份失败
        doReturn(result(List.of(), kp(1, "什么是代理模式？", 1))).when(extractionService).extract(anyList(), any());
        DraftView confirmedGap = gapDraft(GAPS);
        doThrow(new LlmException(ErrorCode.LLM_TIMEOUT)).when(extractionService).extract(anyList(), any());
        DraftView failedGap = gapDraft(GAPS);

        List<DraftSummary> list = draftService.list(USER_ID);

        assertThat(list).extracting(DraftSummary::id).doesNotContain(confirmedGap.id()).contains(failedGap.id());
        assertThat(list).extracting(DraftSummary::id).containsAll(imports.subList(1, 11));
        assertThat(list).hasSize(11);
    }

    @Test
    void 知识库详情的缺口来源不带题号() {
        doReturn(result(List.of(), kp(1, "什么是代理模式？", 1, 2))).when(extractionService).extract(anyList(), any());
        DraftView draft = gapDraft(GAPS);
        long kpId = knowledgePointMapper.selectOne(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getTitle, "什么是代理模式？")).getId();

        KnowledgePointDetail detail = libraryService.detail(USER_ID, kpId);

        assertThat(detail.sources()).singleElement().satisfies(ref -> {
            assertThat(ref.name()).isEqualTo(draft.sourceName());
            assertThat(ref.questionIds()).isEmpty();
        });
    }
}
