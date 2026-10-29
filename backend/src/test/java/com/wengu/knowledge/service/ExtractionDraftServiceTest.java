package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.BatchView;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.service.QuestionSplitService;
import com.wengu.source.service.SourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用真实的 MySQL 和模拟的模型测试抽取流程：排队、重试、失败时保存、重启中断、用户重试。
 * 材料通过真实的 SourceService 导入，只把切分和抽取两处模型调用换成模拟对象；
 * 线程池换成 {@link ManualTaskExecutor}，调用 runAll() 时才执行后台任务。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExtractionDraftServiceTest {

    private static final String CONTENT = """
            1. 说说 HashMap 的原理？
            追问：为什么容量是 2 的幂？
            2. 什么是 MVCC？
            3. Redis 为什么快？
            """;

    @Autowired
    private ExtractionDraftService draftService;

    @Autowired
    private ExtractionRecovery recovery;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private ExtractionDraftMapper draftMapper;

    @Autowired
    private ExtractionBatchMapper batchMapper;

    @Autowired
    private ExtractionDraftItemMapper itemMapper;

    @MockitoBean
    private QuestionSplitService splitService;

    @MockitoBean
    private KnowledgeExtractionService extractionService;

    /** 字段类型要和原来的 bean 一致，@TestBean 才能按名称替换 */
    @TestBean(name = "extractionExecutor")
    private TaskExecutor testExecutor;

    private ManualTaskExecutor extractionExecutor;

    private long sourceId;

    static TaskExecutor testExecutor() {
        return new ManualTaskExecutor();
    }

    @BeforeEach
    void importSource() {
        extractionExecutor = (ManualTaskExecutor) testExecutor;
        extractionExecutor.reset();
        itemMapper.delete(null);
        batchMapper.delete(null);
        draftMapper.delete(null);
        when(splitService.split(anyString())).thenReturn(new LlmResult<>(new SplitResult(List.of(
                new SplitQuestion("说说 HashMap 的原理？", List.of("为什么容量是 2 的幂？")),
                new SplitQuestion("什么是 MVCC？", List.of()),
                new SplitQuestion("Redis 为什么快？", List.of()))), "{...}", "test-model", "stop", 10, 10));
        sourceId = sourceService.importQuestions(new ImportQuestionsRequest("Java 基础", CONTENT)).id();
    }

    private static ExtractedKnowledgePoint kp(int order, String title, Integer... ids) {
        return new ExtractedKnowledgePoint(order, title, "要点", KnowledgePointType.MECHANISM, List.of(ids));
    }

    private static LlmResult<ExtractionResult> result(ExtractedKnowledgePoint... kps) {
        return new LlmResult<>(new ExtractionResult(List.of(), List.of(kps)), "raw-" + kps.length,
                "test-model", "stop", 1000, 500);
    }

    /** 覆盖全部 3 道题的合格结果，故意打乱顺序，检查保存时按 order 排列。 */
    private static LlmResult<ExtractionResult> validResult() {
        return result(
                kp(2, "HashMap 的容量为什么是 2 的幂？", 1),
                kp(1, "HashMap 的 put 流程是怎样的？", 1),
                kp(3, "MVCC 是怎么实现的？", 2),
                kp(4, "Redis 为什么快？", 3));
    }

    /** 发起抽取，并让后台任务执行完。 */
    private DraftView createAndRun(List<Integer> questionIds) {
        DraftView view = draftService.create(new CreateDraftRequest(sourceId, questionIds));
        extractionExecutor.runAll();
        return draftService.get(view.id());
    }

    /** 草稿的所有批次，包括重试前的旧行，按 id 排列。 */
    private List<ExtractionBatch> batches(DraftView view) {
        return batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, view.id())
                .orderByAsc(ExtractionBatch::getId));
    }

    private ExtractionBatch onlyBatch(DraftView view) {
        List<ExtractionBatch> batches = batches(view);
        assertThat(batches).hasSize(1);
        return batches.getFirst();
    }

    @Test
    void 发起后立即返回排队中的草稿且不调用模型() {
        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.RUNNING);
        assertThat(view.batches()).containsExactly(new BatchView(1, BatchStatus.PENDING, List.of(1, 2, 3)));
        assertThat(view.items()).isEmpty();
        verify(extractionService, never()).extract(any());

        ExtractionBatch batch = onlyBatch(view);
        assertThat(batch.getAttempts()).isZero();
        assertThat(batch.getQuestions()).contains("说说 HashMap 的原理？（追问：为什么容量是 2 的幂？）");
    }

    @Test
    void 后台一次成功时条目按顺序保存() {
        when(extractionService.extract(anyList())).thenReturn(validResult());

        DraftView view = createAndRun(null);

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.batches()).containsExactly(new BatchView(1, BatchStatus.READY, List.of(1, 2, 3)));
        assertThat(view.items()).extracting(DraftItemView::order).containsExactly(1, 2, 3, 4);
        assertThat(view.items().get(0).title()).isEqualTo("HashMap 的 put 流程是怎样的？");
        assertThat(view.items().get(2).sourceQuestionIds()).containsExactly(2);

        ExtractionBatch batch = onlyBatch(view);
        assertThat(batch.getAttempts()).isEqualTo(1);
        assertThat(batch.getRawOutput()).isEqualTo("raw-4");
        assertThat(batch.getError()).isNull();
        assertThat(batch.getPromptVersion()).isEqualTo(KnowledgeExtractionService.PROMPT_VERSION);
        assertThat(batch.getModel()).isEqualTo("test-model");
        assertThat(batch.getStartedAt()).isNotNull();
        assertThat(batch.getFinishedAt()).isNotNull();
    }

    @Test
    void 第一次校验不通过时重试一次且token累加() {
        when(extractionService.extract(anyList()))
                .thenReturn(result(kp(1, "只覆盖了第 1 题", 1)))
                .thenReturn(validResult());

        DraftView view = createAndRun(null);

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        ExtractionBatch batch = onlyBatch(view);
        assertThat(batch.getAttempts()).isEqualTo(2);
        assertThat(batch.getError()).isNull();
        assertThat(batch.getPromptTokens()).isEqualTo(2000);
        assertThat(batch.getCompletionTokens()).isEqualTo(1000);
        verify(extractionService, times(2)).extract(anyList());
    }

    @Test
    void 两次都失败时保存失败的批次和原始输出() {
        when(extractionService.extract(anyList()))
                .thenThrow(new LlmOutputParseException("返回内容不符合要求的 JSON 结构",
                        new LlmResult<>(null, "不是 JSON", "test-model", "stop", 1000, 5), new RuntimeException()))
                .thenReturn(result(kp(1, "只覆盖了第 1 题", 1)));

        DraftView view = createAndRun(null);

        assertThat(view.status()).isEqualTo(DraftStatus.FAILED);
        assertThat(view.items()).isEmpty();
        ExtractionBatch batch = onlyBatch(view);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getAttempts()).isEqualTo(2);
        assertThat(batch.getRawOutput()).isEqualTo("raw-1");
        assertThat(batch.getError()).contains("以下题目没有对应的知识点：[2, 3]");
        assertThat(batch.getPromptTokens()).isEqualTo(2000);
        assertThat(itemMapper.selectCount(null)).isZero();
    }

    @Test
    void 模型调用抛异常时不重试并保存失败的批次() {
        when(extractionService.extract(anyList())).thenThrow(new IllegalStateException("连接超时"));

        DraftView view = createAndRun(null);

        assertThat(view.status()).isEqualTo(DraftStatus.FAILED);
        ExtractionBatch batch = onlyBatch(view);
        assertThat(batch.getAttempts()).isEqualTo(1);
        assertThat(batch.getError()).isEqualTo("IllegalStateException: 连接超时");
        assertThat(batch.getRawOutput()).isNull();
        assertThat(batch.getModel()).isNull();
        verify(extractionService, times(1)).extract(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 只抽取勾选的题且题号保持不变() {
        when(extractionService.extract(anyList())).thenReturn(result(kp(1, "Redis 为什么快？", 3)));

        DraftView view = createAndRun(List.of(3));

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.batches().getFirst().questionIds()).containsExactly(3);
        ArgumentCaptor<List<ExtractionQuestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(extractionService).extract(captor.capture());
        assertThat(captor.getValue()).containsExactly(new ExtractionQuestion(3, "Redis 为什么快？"));
    }

    @Test
    void 勾选的题号不存在时报错且不建草稿() {
        assertThatThrownBy(() -> draftService.create(new CreateDraftRequest(sourceId, List.of(9))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(draftMapper.selectCount(null)).isZero();
        assertThat(batchMapper.selectCount(null)).isZero();
    }

    @Test
    void 材料或草稿不存在时报404() {
        assertThatThrownBy(() -> draftService.create(new CreateDraftRequest(sourceId + 100, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> draftService.get(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> draftService.retry(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 队列已满时批次直接失败() {
        extractionExecutor.setFull(true);

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.FAILED);
        assertThat(onlyBatch(view).getError()).isEqualTo(ExtractionWorker.QUEUE_FULL);
    }

    @Test
    void 重启时排队中和执行中的批次标为失败且之后不再执行() {
        DraftView pending = draftService.create(new CreateDraftRequest(sourceId, null));
        DraftView running = draftService.create(new CreateDraftRequest(sourceId, null));
        // 模拟上次运行时第二份草稿正在调用模型
        batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, running.id())
                .set(ExtractionBatch::getStatus, BatchStatus.RUNNING));

        assertThat(recovery.failInterrupted()).isEqualTo(2);

        for (DraftView view : List.of(pending, running)) {
            assertThat(draftService.get(view.id()).status()).isEqualTo(DraftStatus.FAILED);
            ExtractionBatch batch = onlyBatch(view);
            assertThat(batch.getError()).isEqualTo(ExtractionRecovery.INTERRUPTED);
            assertThat(batch.getFinishedAt()).isNotNull();
        }
        // 线程池里残留的任务不会再领取已经标为失败的批次
        extractionExecutor.runAll();
        verify(extractionService, never()).extract(any());
        // 已经完成的批次不受影响
        assertThat(recovery.failInterrupted()).isZero();
    }

    @Test
    void 重试时为失败的批次新增一行且旧行保留() {
        when(extractionService.extract(anyList()))
                .thenThrow(new IllegalStateException("连接超时"))
                .thenReturn(validResult());
        DraftView failed = createAndRun(null);
        assertThat(failed.status()).isEqualTo(DraftStatus.FAILED);

        DraftView retrying = draftService.retry(failed.id());
        assertThat(retrying.status()).isEqualTo(DraftStatus.RUNNING);
        assertThat(retrying.batches()).containsExactly(new BatchView(1, BatchStatus.PENDING, List.of(1, 2, 3)));

        extractionExecutor.runAll();
        DraftView view = draftService.get(failed.id());
        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.items()).hasSize(4);

        List<ExtractionBatch> batches = batches(view);
        assertThat(batches).extracting(ExtractionBatch::getBatchNo).containsExactly(1, 1);
        assertThat(batches).extracting(ExtractionBatch::getStatus)
                .containsExactly(BatchStatus.FAILED, BatchStatus.READY);
        assertThat(batches.getFirst().getError()).isEqualTo("IllegalStateException: 连接超时");
    }

    @Test
    void 抽取中或没有失败的批次时不能重试() {
        DraftView running = draftService.create(new CreateDraftRequest(sourceId, null));
        assertThatThrownBy(() -> draftService.retry(running.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        when(extractionService.extract(anyList())).thenReturn(validResult());
        extractionExecutor.runAll();
        assertThatThrownBy(() -> draftService.retry(running.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(batchMapper.selectCount(null)).isEqualTo(1);
    }
}
