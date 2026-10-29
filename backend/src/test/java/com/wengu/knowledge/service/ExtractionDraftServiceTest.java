package com.wengu.knowledge.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionDraft;
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
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * 用真实的 MySQL 和模拟的模型测试抽取流程：重试、失败时保存草稿、按勾选抽取。
 * 材料通过真实的 SourceService 导入，只把切分和抽取两处模型调用换成模拟对象。
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
    private SourceService sourceService;

    @Autowired
    private ExtractionDraftMapper draftMapper;

    @Autowired
    private ExtractionDraftItemMapper itemMapper;

    @MockitoBean
    private QuestionSplitService splitService;

    @MockitoBean
    private KnowledgeExtractionService extractionService;

    private long sourceId;

    @BeforeEach
    void importSource() {
        itemMapper.delete(null);
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

    private ExtractionDraft saved(DraftView view) {
        return draftMapper.selectById(view.id());
    }

    @Test
    void 一次成功时条目按顺序保存() {
        when(extractionService.extract(anyList())).thenReturn(validResult());

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.items()).extracting(DraftItemView::order).containsExactly(1, 2, 3, 4);
        assertThat(view.items().get(0).title()).isEqualTo("HashMap 的 put 流程是怎样的？");
        assertThat(view.items().get(2).sourceQuestionIds()).containsExactly(2);

        ExtractionDraft draft = saved(view);
        assertThat(draft.getSourceId()).isEqualTo(sourceId);
        assertThat(draft.getAttempts()).isEqualTo(1);
        assertThat(draft.getRawOutput()).isEqualTo("raw-4");
        assertThat(draft.getError()).isNull();
        assertThat(draft.getPromptVersion()).isEqualTo(KnowledgeExtractionService.PROMPT_VERSION);
        assertThat(draft.getModel()).isEqualTo("test-model");
        assertThat(draft.getQuestions()).contains("说说 HashMap 的原理？（追问：为什么容量是 2 的幂？）");

        assertThat(draftService.get(view.id())).isEqualTo(view);
    }

    @Test
    void 第一次校验不通过时重试一次且token累加() {
        when(extractionService.extract(anyList()))
                .thenReturn(result(kp(1, "只覆盖了第 1 题", 1)))
                .thenReturn(validResult());

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        ExtractionDraft draft = saved(view);
        assertThat(draft.getAttempts()).isEqualTo(2);
        assertThat(draft.getError()).isNull();
        assertThat(draft.getPromptTokens()).isEqualTo(2000);
        assertThat(draft.getCompletionTokens()).isEqualTo(1000);
        verify(extractionService, times(2)).extract(anyList());
    }

    @Test
    void 两次都失败时保存失败的草稿和原始输出() {
        when(extractionService.extract(anyList()))
                .thenThrow(new LlmOutputParseException("返回内容不符合要求的 JSON 结构",
                        new LlmResult<>(null, "不是 JSON", "test-model", "stop", 1000, 5), new RuntimeException()))
                .thenReturn(result(kp(1, "只覆盖了第 1 题", 1)));

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.FAILED);
        assertThat(view.items()).isEmpty();
        ExtractionDraft draft = saved(view);
        assertThat(draft.getAttempts()).isEqualTo(2);
        assertThat(draft.getRawOutput()).isEqualTo("raw-1");
        assertThat(draft.getError()).contains("以下题目没有对应的知识点：[2, 3]");
        assertThat(draft.getPromptTokens()).isEqualTo(2000);
        assertThat(itemMapper.selectCount(null)).isZero();
    }

    @Test
    void 模型调用抛异常时不重试并保存失败的草稿() {
        when(extractionService.extract(anyList())).thenThrow(new IllegalStateException("连接超时"));

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, null));

        assertThat(view.status()).isEqualTo(DraftStatus.FAILED);
        ExtractionDraft draft = saved(view);
        assertThat(draft.getAttempts()).isEqualTo(1);
        assertThat(draft.getError()).isEqualTo("IllegalStateException: 连接超时");
        assertThat(draft.getRawOutput()).isNull();
        assertThat(draft.getModel()).isNull();
        verify(extractionService, times(1)).extract(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 只抽取勾选的题且题号保持不变() {
        when(extractionService.extract(anyList())).thenReturn(result(kp(1, "Redis 为什么快？", 3)));

        DraftView view = draftService.create(new CreateDraftRequest(sourceId, List.of(3)));

        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        ArgumentCaptor<List<ExtractionQuestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(extractionService).extract(captor.capture());
        assertThat(captor.getValue()).containsExactly(new ExtractionQuestion(3, "Redis 为什么快？"));
    }

    @Test
    void 勾选的题号不存在时报错且不调用模型() {
        assertThatThrownBy(() -> draftService.create(new CreateDraftRequest(sourceId, List.of(9))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(extractionService, never()).extract(any());
        assertThat(draftMapper.selectCount(null)).isZero();
    }

    @Test
    void 材料或草稿不存在时报404() {
        assertThatThrownBy(() -> draftService.create(new CreateDraftRequest(sourceId + 100, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> draftService.get(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
