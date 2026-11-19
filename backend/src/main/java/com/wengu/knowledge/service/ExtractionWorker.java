package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.CodeExtractionResult;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.entity.ExtractionDraftItemCode;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemCodeMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.CodeFileContent;
import com.wengu.source.entity.SourceType;
import com.wengu.source.service.SourceService;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在后台线程池中执行抽取：按批号顺序处理草稿中排队的批次，每批调用一次模型，结果写回批次和条目（决策 013）。
 * 每批都带上去重上下文，已有的内容不再重复抽取（决策 014）。服务重启时未完成的批次由 {@link ExtractionRecovery} 处理。
 * <p>
 * 这里的代码运行在线程池的线程里，不使用 CurrentUser：登录信息只在请求线程上，到这里就读不到了。
 * 用户 id 一律取自批次。
 */
@Service
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    /** 第一次的结果解析失败或校验不通过时，再试一次。 */
    private static final int MAX_ATTEMPTS = 2;

    static final String QUEUE_FULL = "任务队列已满";

    static final String SKIPPED = "前面一批调用模型出错，这一批没有执行";

    /** 源码抽取成功、但丢掉了不合格的知识点时，batch.error 以这句开头 */
    static final String DROPPED_PREFIX = "丢掉了不合格的知识点：";

    private final KnowledgeExtractionService extractionService;
    private final CodeExtractionService codeExtractionService;
    private final KnowledgePointService knowledgePointService;
    private final SourceService sourceService;
    private final ExtractionDraftMapper draftMapper;
    private final ExtractionBatchMapper batchMapper;
    private final ExtractionDraftItemMapper itemMapper;
    private final ExtractionDraftItemCodeMapper itemCodeMapper;
    private final TaskExecutor executor;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    /** 要写入的一个条目；code 是源码条目特有的数据，题目条目为 null */
    private record NewItem(ExtractionDraftItem item, ExtractionDraftItemCode code) {
    }

    /** 一批成功后要写回的内容：条目，以及被已有内容覆盖的题（源码没有去重，为 "[]"） */
    private record Outcome(List<NewItem> items, String covered) {
    }

    public ExtractionWorker(KnowledgeExtractionService extractionService, CodeExtractionService codeExtractionService,
                            KnowledgePointService knowledgePointService, SourceService sourceService,
                            ExtractionDraftMapper draftMapper, ExtractionBatchMapper batchMapper,
                            ExtractionDraftItemMapper itemMapper, ExtractionDraftItemCodeMapper itemCodeMapper,
                            @Qualifier("extractionExecutor") TaskExecutor executor, Clock clock,
                            JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.extractionService = extractionService;
        this.codeExtractionService = codeExtractionService;
        this.knowledgePointService = knowledgePointService;
        this.sourceService = sourceService;
        this.draftMapper = draftMapper;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.itemCodeMapper = itemCodeMapper;
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

    /**
     * 按批号顺序执行草稿中排队的批次。
     * <p>
     * 某一批输出解析失败或校验不通过时，照常执行下一批；模型调用本身出错（网络错误、Key 不对）时，
     * 后面的批次大概率也会失败，不再调用，直接标为失败，用户处理好之后一起重试。
     */
    void run(long draftId) {
        List<ExtractionBatch> pending = batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId)
                .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                .orderByAsc(ExtractionBatch::getBatchNo));
        for (ExtractionBatch batch : pending) {
            boolean callSucceeded;
            try {
                callSucceeded = runBatch(batch);
            } catch (RuntimeException e) {
                // 走到这里的是写库、解析 JSON 这类意外错误，模型调用的异常在 runBatch 中已经处理
                log.error("抽取批次执行出错: batchId={}", batch.getId(), e);
                markFailed(batch.getId(), e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            if (!callSucceeded) {
                skipPending(draftId, batch.getErrorCode());
                return;
            }
        }
    }

    /**
     * 执行一批，结果写回批次和条目。
     *
     * @return 模型调用本身是否正常；为 false 时（网络错误、Key 不对）后面的批次不再执行
     */
    private boolean runBatch(ExtractionBatch batch) {
        // 源码材料（#90）只有一批，输入是导入的全部文件；题目材料为 null
        List<CodeFileContent> files = codeFiles(batch);
        // 只领取仍在排队的批次：排队期间它可能已被标为失败（例如重启处理）
        Instant startedAt = Instant.now(clock);
        int claimed = batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getId, batch.getId())
                .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                .set(ExtractionBatch::getStatus, BatchStatus.RUNNING)
                .set(ExtractionBatch::getStartedAt, startedAt)
                .set(ExtractionBatch::getPromptVersion, files == null ? KnowledgeExtractionService.PROMPT_VERSION
                        : CodeExtractionService.PROMPT_VERSION));
        if (claimed == 0) {
            return true;
        }

        List<ExtractionQuestion> questions = List.of(
                jsonMapper.readValue(batch.getQuestions(), ExtractionQuestion[].class));
        // 执行时才构造上下文，前面刚完成的批次也能用上；失败时也保存，为 #9 保留数据。源码不做去重（决策 050）
        DedupContext context = files == null ? buildContext(batch) : null;
        if (context != null) {
            batch.setContext(jsonMapper.writeValueAsString(context.entries()));
        }
        Outcome outcome;
        boolean callSucceeded = true;
        try {
            outcome = files == null ? extractQuestions(questions, context, batch) : extractCode(files, batch);
        } catch (RuntimeException e) {
            // 模型调用本身出错，重试也没用。给用户看的原因按 LlmService 的分类（决策 032）
            batch.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            batch.setErrorCode((e instanceof LlmException llm ? llm.getErrorCode() : ErrorCode.LLM_FAILED).name());
            log.error("知识点抽取调用模型失败: batchId={}", batch.getId(), e);
            outcome = null;
            callSucceeded = false;
        }
        if (outcome == null && callSucceeded) {
            // 模型有回应，但重试后输出仍不合格
            batch.setErrorCode(ErrorCode.LLM_INVALID_OUTPUT.name());
        }
        batch.setStatus(outcome == null ? BatchStatus.FAILED : BatchStatus.READY);
        batch.setFinishedAt(Instant.now(clock));
        if (outcome != null) {
            batch.setCovered(outcome.covered());
        }

        List<NewItem> items = outcome == null ? List.of() : outcome.items();
        // updateById 只更新非空字段。批次在这之前只写过状态和开始时间，其余字段在库里都是空的，不会留下旧值
        transactionTemplate.executeWithoutResult(status -> {
            batchMapper.updateById(batch);
            for (NewItem newItem : items) {
                itemMapper.insert(newItem.item());
                if (newItem.code() != null) {
                    newItem.code().setItemId(newItem.item().getId());
                    itemCodeMapper.insert(newItem.code());
                }
            }
        });
        return callSucceeded;
    }

    /** 这一批的材料是源码时，返回导入的全部文件；题目材料（包括接着问产生的草稿）返回 null。 */
    private List<CodeFileContent> codeFiles(ExtractionBatch batch) {
        ExtractionDraft draft = draftMapper.selectById(batch.getDraftId());
        if (draft.getSourceId() == null
                || sourceService.getType(batch.getUserId(), draft.getSourceId()) != SourceType.CODE) {
            return null;
        }
        return sourceService.getCodeFiles(batch.getUserId(), draft.getSourceId());
    }

    private Outcome extractQuestions(List<ExtractionQuestion> questions, DedupContext context, ExtractionBatch batch) {
        ExtractionResult result = extractWithRetry(questions, context, batch);
        if (result == null) {
            return null;
        }
        return new Outcome(toItems(result, batch).stream().map(item -> new NewItem(item, null)).toList(),
                jsonMapper.writeValueAsString(context.resolve(result.covered())));
    }

    private Outcome extractCode(List<CodeFileContent> files, ExtractionBatch batch) {
        CodeExtractionResult result = extractCodeWithRetry(files, batch);
        return result == null ? null : new Outcome(toCodeItems(result, batch), "[]");
    }

    /**
     * 源码抽取。不合格的知识点单独丢掉，合格的照样留下（决策 050）：丢了哪些、为什么，记在 batch.error 里，供以后分析（#9）。
     * 只有输出解析失败、或者抽出的全都不合格时才重试；模型调用本身的异常直接抛出，和 {@link #extractWithRetry} 相同。
     */
    private CodeExtractionResult extractCodeWithRetry(List<CodeFileContent> files, ExtractionBatch batch) {
        Map<String, String> byPath = files.stream()
                .collect(Collectors.toMap(CodeFileContent::path, CodeFileContent::content));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            batch.setAttempts(attempt);
            try {
                LlmResult<CodeExtractionResult> result = codeExtractionService.extract(files);
                record(batch, result);
                CodeExtractionValidator.Checked checked = CodeExtractionValidator.check(byPath, result.content());
                if (checked.accepted() != null) {
                    batch.setError(checked.problems().isEmpty() ? null
                            : DROPPED_PREFIX + "\n" + String.join("\n", checked.problems()));
                    if (!checked.problems().isEmpty()) {
                        log.warn("源码抽取丢掉了不合格的知识点: batchId={}, problems={}", batch.getId(), checked.problems());
                    }
                    return new CodeExtractionResult(checked.accepted());
                }
                batch.setError(String.join("\n", checked.problems()));
                log.warn("源码抽取整批不能用（第 {} 次）: batchId={}, problems={}", attempt, batch.getId(), checked.problems());
            } catch (LlmOutputParseException e) {
                record(batch, e.getResult());
                batch.setError(e.getMessage());
                log.warn("源码抽取结果无法解析（第 {} 次）: batchId={}, {}", attempt, batch.getId(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 去重上下文（决策 014）：用户已有的知识点，加上同一草稿中其他已成功批次的条目，按批号、批内顺序排列。
     * 成功的批次不会被重试，它的条目一直有效。
     */
    private DedupContext buildContext(ExtractionBatch batch) {
        Map<Long, Integer> readyBatchNos = batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                        .select(ExtractionBatch::getId, ExtractionBatch::getBatchNo)
                        .eq(ExtractionBatch::getDraftId, batch.getDraftId())
                        .eq(ExtractionBatch::getStatus, BatchStatus.READY))
                .stream()
                .collect(Collectors.toMap(ExtractionBatch::getId, ExtractionBatch::getBatchNo));
        List<DedupContext.Existing> items = readyBatchNos.isEmpty() ? List.of()
                : itemMapper.selectList(new LambdaQueryWrapper<ExtractionDraftItem>()
                        .in(ExtractionDraftItem::getBatchId, readyBatchNos.keySet()))
                .stream()
                .sorted(Comparator.comparing((ExtractionDraftItem item) -> readyBatchNos.get(item.getBatchId()))
                        .thenComparing(ExtractionDraftItem::getItemOrder))
                .map(item -> new DedupContext.Existing(item.getId(), item.getTitle()))
                .toList();
        return DedupContext.of(knowledgePointService.listTitles(batch.getUserId()), items);
    }

    /**
     * 前面一批调用模型出错时，把草稿中还在排队的批次标为失败。
     * 失败原因用前面那一批的：这几批没有执行，原因就是模型调用出了那个错（决策 032）
     */
    private void skipPending(long draftId, String errorCode) {
        batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId)
                .eq(ExtractionBatch::getStatus, BatchStatus.PENDING)
                .set(ExtractionBatch::getStatus, BatchStatus.FAILED)
                .set(ExtractionBatch::getError, SKIPPED)
                .set(ExtractionBatch::getErrorCode, errorCode)
                .set(ExtractionBatch::getFinishedAt, Instant.now(clock)));
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
     * 只有输出解析失败或校验不通过时才重试；模型调用本身抛出的异常（网络错误、Key 不对）重试也没用，直接抛给调用方。
     * 最后一次只剩漏题时也返回结果，漏题的说明留在 batch.error 中，供以后分析（#9）。
     */
    private ExtractionResult extractWithRetry(List<ExtractionQuestion> questions, DedupContext context,
                                              ExtractionBatch batch) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            batch.setAttempts(attempt);
            try {
                LlmResult<ExtractionResult> result = extractionService.extract(questions, context);
                record(batch, result);
                List<String> problems = ExtractionValidator.validate(questions, context, result.content());
                if (problems.isEmpty()) {
                    batch.setError(null);
                    return result.content();
                }
                // 最后一次只剩漏题：不让一两道题拖垮整批，照样算成功；漏掉的题在确认页提示，可以单独再抽（#22）
                if (attempt == MAX_ATTEMPTS && ExtractionValidator.onlyMissing(problems)) {
                    batch.setError(problems.getFirst());
                    log.warn("知识点抽取重试后仍有漏题，按成功处理: batchId={}, {}", batch.getId(), problems.getFirst());
                    return result.content();
                }
                batch.setError(String.join("\n", problems));
                log.warn("知识点抽取校验不通过（第 {} 次）: batchId={}, problems={}", attempt, batch.getId(), problems);
            } catch (LlmOutputParseException e) {
                record(batch, e.getResult());
                batch.setError(e.getMessage());
                log.warn("知识点抽取结果无法解析（第 {} 次）: batchId={}, {}", attempt, batch.getId(), e.getMessage());
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

    /** 源码条目：类型固定为个人经历，没有来源题号；细节、代码依据等特有的数据另存一行（决策 050）。 */
    private List<NewItem> toCodeItems(CodeExtractionResult result, ExtractionBatch batch) {
        return result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(CodeExtractionResult.KnowledgePoint::order))
                .map(kp -> {
                    ExtractionDraftItem item = new ExtractionDraftItem();
                    item.setUserId(batch.getUserId());
                    item.setDraftId(batch.getDraftId());
                    item.setBatchId(batch.getId());
                    item.setItemOrder(kp.order());
                    item.setTitle(kp.title().strip());
                    item.setSummary(kp.summary().strip());
                    item.setType(KnowledgePointType.EXPERIENCE);
                    item.setSourceQuestionIds("");
                    ExtractionDraftItemCode code = new ExtractionDraftItemCode();
                    code.setUserId(batch.getUserId());
                    code.setDetails(kp.summaryDetails() == null || kp.summaryDetails().isBlank() ? null
                            : kp.summaryDetails().strip());
                    code.setEvidence(jsonMapper.writeValueAsString(kp.evidence()));
                    code.setUnverifiable(jsonMapper.writeValueAsString(
                            kp.unverifiable() == null ? List.of() : kp.unverifiable()));
                    code.setPrerequisites(jsonMapper.writeValueAsString(
                            kp.prerequisites() == null ? List.of() : kp.prerequisites()));
                    return new NewItem(item, code);
                })
                .toList();
    }
}
