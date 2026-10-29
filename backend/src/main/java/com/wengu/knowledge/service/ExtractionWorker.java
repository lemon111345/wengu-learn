package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 在后台线程池中执行抽取：按批号顺序处理草稿中排队的批次，每批调用一次模型，结果写回批次和条目（决策 013）。
 * 服务重启时未完成的批次由 {@link ExtractionRecovery} 处理。
 * <p>
 * 这里的代码运行在线程池的线程里，不使用 CurrentUser：多用户模式下当前用户来自请求线程，到这里就读不到了。
 * 用户 id 一律取自批次。
 */
@Service
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    /** 第一次的结果解析失败或校验不通过时，再试一次。 */
    private static final int MAX_ATTEMPTS = 2;

    static final String QUEUE_FULL = "任务队列已满";

    private final KnowledgeExtractionService extractionService;
    private final ExtractionBatchMapper batchMapper;
    private final ExtractionDraftItemMapper itemMapper;
    private final TaskExecutor executor;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public ExtractionWorker(KnowledgeExtractionService extractionService, ExtractionBatchMapper batchMapper,
                            ExtractionDraftItemMapper itemMapper,
                            @Qualifier("extractionExecutor") TaskExecutor executor, Clock clock,
                            JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.extractionService = extractionService;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.executor = executor;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 把草稿交给线程池执行。必须在批次所在的事务提交之后调用，否则后台线程可能读不到刚插入的批次。
     * 队列已满时不抛异常，这些批次直接标为失败，用户稍后重试即可。
     */
    public void submit(long draftId) {
        try {
            executor.execute(() -> run(draftId));
        } catch (TaskRejectedException e) {
            log.warn("抽取任务队列已满: draftId={}", draftId);
            batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                    .eq(ExtractionBatch::getDraftId, draftId)
                    .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                    .set(ExtractionBatch::getStatus, BatchStatus.FAILED)
                    .set(ExtractionBatch::getError, QUEUE_FULL)
                    .set(ExtractionBatch::getFinishedAt, Instant.now(clock)));
        }
    }

    /** 按批号顺序执行草稿中排队的批次。 */
    void run(long draftId) {
        List<ExtractionBatch> pending = batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId)
                .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                .orderByAsc(ExtractionBatch::getBatchNo));
        for (ExtractionBatch batch : pending) {
            try {
                runBatch(batch);
            } catch (RuntimeException e) {
                // 走到这里的是写库、解析 JSON 这类意外错误，模型调用的异常在 extractWithRetry 中已经处理
                log.error("抽取批次执行出错: batchId={}", batch.getId(), e);
                markFailed(batch.getId(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void runBatch(ExtractionBatch batch) {
        // 只领取仍在排队的批次：排队期间它可能已被标为失败（例如重启处理）
        Instant startedAt = Instant.now(clock);
        int claimed = batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getId, batch.getId())
                .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                .set(ExtractionBatch::getStatus, BatchStatus.RUNNING)
                .set(ExtractionBatch::getStartedAt, startedAt)
                .set(ExtractionBatch::getPromptVersion, KnowledgeExtractionService.PROMPT_VERSION));
        if (claimed == 0) {
            return;
        }

        List<ExtractionQuestion> questions = List.of(
                jsonMapper.readValue(batch.getQuestions(), ExtractionQuestion[].class));
        ExtractionResult result = extractWithRetry(questions, batch);
        batch.setStatus(result == null ? BatchStatus.FAILED : BatchStatus.READY);
        batch.setFinishedAt(Instant.now(clock));

        List<ExtractionDraftItem> items = result == null ? List.of() : toItems(result, batch);
        // updateById 只更新非空字段。批次在这之前只写过状态和开始时间，其余字段在库里都是空的，不会留下旧值
        transactionTemplate.executeWithoutResult(status -> {
            batchMapper.updateById(batch);
            items.forEach(itemMapper::insert);
        });
    }

    /** 意外出错时尽量把批次标为失败；连这一步也失败时（比如数据库连不上），只能等重启处理。 */
    private void markFailed(long batchId, String error) {
        try {
            batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                    .eq(ExtractionBatch::getId, batchId)
                    .set(ExtractionBatch::getStatus, BatchStatus.FAILED)
                    .set(ExtractionBatch::getError, error)
                    .set(ExtractionBatch::getFinishedAt, Instant.now(clock)));
        } catch (RuntimeException e) {
            log.error("标记抽取批次失败时出错: batchId={}", batchId, e);
        }
    }

    /**
     * 调用模型抽取，把每次调用的信息记到 batch 上，返回通过校验的结果；失败时返回 null，原因记在 batch.error。
     * <p>
     * 只有输出解析失败或校验不通过时才重试；模型调用本身抛出的异常（网络错误、Key 不对）重试也没用。
     */
    private ExtractionResult extractWithRetry(List<ExtractionQuestion> questions, ExtractionBatch batch) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            batch.setAttempts(attempt);
            try {
                LlmResult<ExtractionResult> result = extractionService.extract(questions);
                record(batch, result);
                List<String> problems = ExtractionValidator.validate(questions, result.content());
                if (problems.isEmpty()) {
                    batch.setError(null);
                    return result.content();
                }
                batch.setError(String.join("\n", problems));
                log.warn("知识点抽取校验不通过（第 {} 次）: batchId={}, problems={}", attempt, batch.getId(), problems);
            } catch (LlmOutputParseException e) {
                record(batch, e.getResult());
                batch.setError(e.getMessage());
                log.warn("知识点抽取结果无法解析（第 {} 次）: batchId={}, {}", attempt, batch.getId(), e.getMessage());
            } catch (RuntimeException e) {
                batch.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("知识点抽取调用模型失败: batchId={}", batch.getId(), e);
                return null;
            }
        }
        return null;
    }

    /** 原始输出和模型名记最后一次的，token 累加，统计的是实际费用。 */
    private static void record(ExtractionBatch batch, LlmResult<?> result) {
        batch.setRawOutput(result.rawText());
        batch.setModel(result.model());
        batch.setPromptTokens(add(batch.getPromptTokens(), result.promptTokens()));
        batch.setCompletionTokens(add(batch.getCompletionTokens(), result.completionTokens()));
    }

    private static Integer add(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + b;
    }

    private static List<ExtractionDraftItem> toItems(ExtractionResult result, ExtractionBatch batch) {
        return result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(ExtractedKnowledgePoint::order))
                .map(kp -> {
                    ExtractionDraftItem item = new ExtractionDraftItem();
                    item.setUserId(batch.getUserId());
                    item.setDraftId(batch.getDraftId());
                    item.setBatchId(batch.getId());
                    item.setItemOrder(kp.order());
                    item.setTitle(kp.title().strip());
                    item.setSummary(kp.summary() == null ? "" : kp.summary().strip());
                    item.setType(kp.type());
                    item.setSourceQuestionIds(kp.sourceQuestionIds().stream().map(String::valueOf)
                            .collect(Collectors.joining(",")));
                    return item;
                })
                .toList();
    }
}
