package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.CodeEvidence;
import com.wengu.knowledge.dto.CodeExtractionResult;
import com.wengu.knowledge.dto.CodeExtractionResult.KnowledgePoint;
import com.wengu.knowledge.dto.CodePrerequisite;
import com.wengu.knowledge.dto.ConfirmDraftRequest;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.KnowledgePointDetail;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.entity.ExtractionDraftItemCode;
import com.wengu.knowledge.entity.KpCode;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemCodeMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpCodeMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.source.dto.CodeFileContent;
import com.wengu.source.dto.ImportCodeRequest;
import com.wengu.source.dto.ImportCodeRequest.CodeFile;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 源码抽取的流程（#90，决策 050）：用真实的 MySQL 导入源码、建草稿，后台任务用 {@link ManualTaskExecutor} 手动执行，
 * 只把模型换成模拟对象。题目抽取的流程见 {@link ExtractionDraftServiceTest}。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CodeDraftTest {

    private static final long USER_ID = 1L;

    private static final String CHAT_SERVICE = "class ChatService {\n    void send() {}\n}\n";

    private static final String RATING_SERVICE = "class RatingService {\n    void rate() {}\n}\n";

    private static final String SUMMARY = "1. 先校验再保存。\n- 先校验内容\n- 校验通过才保存";

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
    private ExtractionDraftItemCodeMapper itemCodeMapper;

    @Autowired
    private KpCodeMapper kpCodeMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private KpSourceMapper kpSourceMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private KnowledgeLibraryService libraryService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CodeExtractionService codeExtractionService;

    @MockitoBean
    private KnowledgeExtractionService extractionService;

    @TestBean(name = "extractionExecutor")
    private TaskExecutor testExecutor;

    private ManualTaskExecutor executor;

    private long sourceId;

    static TaskExecutor testExecutor() {
        return new ManualTaskExecutor();
    }

    @BeforeEach
    void importCode() {
        executor = (ManualTaskExecutor) testExecutor;
        executor.reset();
        kpCodeMapper.delete(null);
        cardStateMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);
        itemCodeMapper.delete(null);
        itemMapper.delete(null);
        batchMapper.delete(null);
        draftMapper.delete(null);
        sourceId = sourceService.importCode(USER_ID, new ImportCodeRequest("wengu-learn",
                List.of(new CodeFile("chat/ChatService.java", CHAT_SERVICE),
                        new CodeFile("chat/RatingService.java", RATING_SERVICE)))).id();
    }

    private static LlmResult<CodeExtractionResult> result(String symbol) {
        KnowledgePoint kp = new KnowledgePoint("CHAIN", List.of(new CodeEvidence("chat/ChatService.java", symbol)), 1,
                List.of(new CodePrerequisite("事务", "要么全做，要么全不做。")), SUMMARY + "\n", " 1. 内容不超过 5000 字。 ",
                " 发一条消息时依次发生了什么？ ", List.of("为什么先校验？"));
        return new LlmResult<>(new CodeExtractionResult(List.of(kp)), "{...}", "test-model", "stop", 100, 50);
    }

    @Test
    void 源码只建一批_条目是个人经历_带代码依据_没有题号() {
        when(codeExtractionService.extract(anyList())).thenReturn(result("ChatService#send"));

        DraftView created = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null));
        assertThat(created.status()).isEqualTo(DraftStatus.RUNNING);
        assertThat(created.batches()).singleElement().satisfies(b -> assertThat(b.questionIds()).isEmpty());
        executor.runAll();

        DraftView view = draftService.get(USER_ID, created.id());
        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.covered()).isEmpty();
        assertThat(view.uncovered()).isEmpty();
        DraftItemView item = view.items().getFirst();
        assertThat(item.title()).isEqualTo("发一条消息时依次发生了什么？");
        assertThat(item.summary()).isEqualTo(SUMMARY);
        assertThat(item.type()).isEqualTo(KnowledgePointType.EXPERIENCE);
        assertThat(item.sourceQuestionIds()).isEmpty();
        assertThat(item.evidence()).containsExactly(new CodeEvidence("chat/ChatService.java", "ChatService#send"));
        verify(codeExtractionService).extract(List.of(new CodeFileContent("chat/ChatService.java", CHAT_SERVICE),
                new CodeFileContent("chat/RatingService.java", RATING_SERVICE)));
        verify(extractionService, never()).extract(anyList(), any());

        ExtractionBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, created.id()));
        assertThat(batch.getPromptVersion()).isEqualTo(CodeExtractionService.PROMPT_VERSION);
        assertThat(batch.getContext()).isNull();
        ExtractionDraftItemCode code = itemCodeMapper.selectById(item.id());
        assertThat(code.getUserId()).isEqualTo(USER_ID);
        assertThat(code.getDetails()).isEqualTo("1. 内容不超过 5000 字。");
        assertThat(code.getUnverifiable()).isEqualTo("[\"为什么先校验？\"]");
        assertThat(code.getPrerequisites()).contains("\"concept\":\"事务\"");
    }

    @Test
    void 有一个不合格时单独丢掉_不重抽_丢掉的原因记在批次上() {
        KnowledgePoint good = result("ChatService#send").content().knowledgePoints().getFirst();
        KnowledgePoint bad = new KnowledgePoint("FAILURE", List.of(new CodeEvidence("chat/ChatService.java", "ChatService#reply")),
                2, List.of(), SUMMARY, "", "回复失败时会怎样？", List.of());
        when(codeExtractionService.extract(anyList())).thenReturn(
                new LlmResult<>(new CodeExtractionResult(List.of(bad, good)), "{...}", "test-model", "stop", 100, 50));

        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();

        DraftView view = draftService.get(USER_ID, draftId);
        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.items()).extracting(DraftItemView::title).containsExactly("发一条消息时依次发生了什么？");
        verify(codeExtractionService, times(1)).extract(anyList());
        ExtractionBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId));
        assertThat(batch.getError()).startsWith(ExtractionWorker.DROPPED_PREFIX)
                .contains("第 2 个知识点：chat/ChatService.java 中没有 reply");
    }

    @Test
    void 依据不存在时重试一次_第二次合格就成功() {
        when(codeExtractionService.extract(anyList()))
                .thenReturn(result("ChatService#reply"))
                .thenReturn(result("ChatService#send"));

        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();

        assertThat(draftService.get(USER_ID, draftId).status()).isEqualTo(DraftStatus.READY);
        verify(codeExtractionService, times(2)).extract(anyList());
    }

    @Test
    void 两次都不合格时这一批失败_重试后可以成功() {
        when(codeExtractionService.extract(anyList()))
                .thenReturn(result("ChatService#reply"))
                .thenReturn(result("ChatService#reply"))
                .thenReturn(result("ChatService#send"));

        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();
        DraftView failed = draftService.get(USER_ID, draftId);
        assertThat(failed.status()).isEqualTo(DraftStatus.FAILED);
        assertThat(failed.batches().getFirst().failureReason()).isEqualTo(ErrorCode.LLM_INVALID_OUTPUT.getMessage());
        assertThat(failed.items()).isEmpty();

        draftService.retry(USER_ID, draftId);
        executor.runAll();
        DraftView retried = draftService.get(USER_ID, draftId);
        assertThat(retried.status()).isEqualTo(DraftStatus.READY);
        assertThat(retried.items()).hasSize(1);
        assertThat(batchMapper.selectList(null)).extracting(ExtractionBatch::getStatus)
                .containsExactlyInAnyOrder(BatchStatus.FAILED, BatchStatus.READY);
    }

    @Test
    void 一个都没抽出也算成功() {
        when(codeExtractionService.extract(anyList())).thenReturn(
                new LlmResult<>(new CodeExtractionResult(List.of()), "{...}", "test-model", "stop", 100, 5));

        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();

        DraftView view = draftService.get(USER_ID, draftId);
        assertThat(view.status()).isEqualTo(DraftStatus.READY);
        assertThat(view.items()).isEmpty();
    }

    @Test
    void 确认后写入kp_code_取消勾选的不写_个人经历不初始化调度状态() {
        when(codeExtractionService.extract(anyList())).thenReturn(twoPoints());
        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();
        List<DraftItemView> items = draftService.get(USER_ID, draftId).items();

        draftService.confirm(USER_ID, draftId, new ConfirmDraftRequest(List.of(items.get(1).id())));

        // 知识点实体和抽取结果里的 KnowledgePoint 同名，这里用 var
        var kps = knowledgePointMapper.selectList(null);
        assertThat(kps).singleElement().satisfies(kp -> {
            assertThat(kp.getType()).isEqualTo(KnowledgePointType.EXPERIENCE);
            assertThat(kp.getSummary()).isEqualTo(SUMMARY);
        });
        long kpId = kps.getFirst().getId();
        KpCode code = kpCodeMapper.selectById(kpId);
        assertThat(code.getSourceId()).isEqualTo(sourceId);
        assertThat(code.getUserId()).isEqualTo(USER_ID);
        assertThat(code.getDetails()).isEqualTo("1. 内容不超过 5000 字。");
        assertThat(code.getEvidence()).contains("ChatService#send");
        assertThat(kpCodeMapper.selectCount(null)).isOne();
        assertThat(cardStateMapper.selectCount(null)).isZero();
    }

    @Test
    void 源码知识点的详情_来源没有题号_带上依据的文件() {
        when(codeExtractionService.extract(anyList())).thenReturn(twoPoints());
        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        executor.runAll();
        draftService.confirm(USER_ID, draftId, null);
        long kpId = knowledgePointMapper.selectList(null).stream()
                .filter(kp -> kp.getTitle().equals("评分前后怎么衔接？"))
                .findFirst().orElseThrow().getId();

        // 详情要按用户设置换算学习日；空库上用户 1 没有设置（同 KnowledgeLibraryServiceTest）
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");

        KnowledgePointDetail detail = libraryService.detail(USER_ID, kpId);

        assertThat(detail.sources()).singleElement().satisfies(source -> {
            assertThat(source.name()).isEqualTo("wengu-learn");
            assertThat(source.questionIds()).isEmpty();
            // 同一个文件引用了两次，只列一次
            assertThat(source.files()).containsExactly("chat/ChatService.java", "chat/RatingService.java");
        });
    }

    /** 两个合格的知识点：第 1 个依据一个文件，第 2 个依据两个文件（其中一个引用了两次） */
    private static LlmResult<CodeExtractionResult> twoPoints() {
        KnowledgePoint first = result("ChatService#send").content().knowledgePoints().getFirst();
        KnowledgePoint second = new KnowledgePoint("CHAIN", List.of(
                new CodeEvidence("chat/ChatService.java", "ChatService"),
                new CodeEvidence("chat/RatingService.java", "RatingService#rate"),
                new CodeEvidence("chat/ChatService.java", "ChatService#send")),
                2, List.of(), SUMMARY, "", "评分前后怎么衔接？", List.of());
        return new LlmResult<>(new CodeExtractionResult(List.of(first, second)), "{...}", "test-model", "stop", 100, 50);
    }

    @Test
    void 源码材料不能传题号() {
        assertThatThrownBy(() -> draftService.create(USER_ID, new CreateDraftRequest(sourceId, List.of(1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(draftMapper.selectCount(null)).isZero();
    }
}
