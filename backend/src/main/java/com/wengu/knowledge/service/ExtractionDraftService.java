package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.service.SourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 从导入的材料中抽取知识点，结果存成草稿，等用户确认（#17）。设计见决策 012。
 * <p>
 * 抽取是同步调用，要 1～3 分钟；改成异步是 #16 的事。
 */
@Service
public class ExtractionDraftService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionDraftService.class);

    /** 第一次的结果解析失败或校验不通过时，再试一次。 */
    private static final int MAX_ATTEMPTS = 2;

    private final SourceService sourceService;
    private final KnowledgeExtractionService extractionService;
    private final ExtractionDraftMapper draftMapper;
    private final ExtractionDraftItemMapper itemMapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public ExtractionDraftService(SourceService sourceService, KnowledgeExtractionService extractionService,
                                  ExtractionDraftMapper draftMapper, ExtractionDraftItemMapper itemMapper,
                                  CurrentUser currentUser, Clock clock, JsonMapper jsonMapper,
                                  TransactionTemplate transactionTemplate) {
        this.sourceService = sourceService;
        this.extractionService = extractionService;
        this.draftMapper = draftMapper;
        this.itemMapper = itemMapper;
        this.currentUser = currentUser;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 抽取知识点并保存草稿。成功和失败都会保存：失败的草稿记下原始输出和原因，为 #9 保留真实数据。
     * <p>
     * 调用模型不在事务里，避免长时间占用数据库连接；拿到结果后再在一个事务里写草稿和条目。
     */
    public DraftView create(CreateDraftRequest request) {
        if (request.sourceId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 sourceId");
        }
        List<SourceQuestion> selected = select(sourceService.getQuestions(request.sourceId()), request.questionIds());
        List<ExtractionQuestion> questions = selected.stream().map(ExtractionDraftService::toExtractionQuestion).toList();

        ExtractionDraft draft = new ExtractionDraft();
        draft.setUserId(currentUser.id());
        draft.setSourceId(request.sourceId());
        draft.setQuestions(jsonMapper.writeValueAsString(questions));
        draft.setPromptVersion(KnowledgeExtractionService.PROMPT_VERSION);
        ExtractionResult result = extractWithRetry(questions, draft);
        draft.setStatus(result == null ? DraftStatus.FAILED : DraftStatus.READY);
        draft.setCreatedAt(Instant.now(clock));

        List<ExtractionDraftItem> items = result == null ? List.of() : toItems(result);
        transactionTemplate.executeWithoutResult(status -> {
            draftMapper.insert(draft);
            for (ExtractionDraftItem item : items) {
                item.setDraftId(draft.getId());
                itemMapper.insert(item);
            }
        });
        return toView(draft, items);
    }

    /** 查看草稿。不存在或不属于当前用户时为 NOT_FOUND。 */
    public DraftView get(long draftId) {
        ExtractionDraft draft = draftMapper.selectById(draftId);
        if (draft == null || draft.getUserId() != currentUser.id()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在");
        }
        List<ExtractionDraftItem> items = itemMapper.selectList(new LambdaQueryWrapper<ExtractionDraftItem>()
                .eq(ExtractionDraftItem::getDraftId, draftId)
                .orderByAsc(ExtractionDraftItem::getItemOrder));
        return toView(draft, items);
    }

    /**
     * 调用模型抽取，把每次调用的信息记到 draft 上，返回通过校验的结果；失败时返回 null，原因记在 draft.error。
     * <p>
     * 只有输出解析失败或校验不通过时才重试；模型调用本身抛出的异常（网络错误、Key 不对）重试也没用。
     */
    private ExtractionResult extractWithRetry(List<ExtractionQuestion> questions, ExtractionDraft draft) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            draft.setAttempts(attempt);
            try {
                LlmResult<ExtractionResult> result = extractionService.extract(questions);
                record(draft, result);
                List<String> problems = ExtractionValidator.validate(questions, result.content());
                if (problems.isEmpty()) {
                    draft.setError(null);
                    return result.content();
                }
                draft.setError(String.join("\n", problems));
                log.warn("知识点抽取校验不通过（第 {} 次）: problems={}", attempt, problems);
            } catch (LlmOutputParseException e) {
                record(draft, e.getResult());
                draft.setError(e.getMessage());
                log.warn("知识点抽取结果无法解析（第 {} 次）: {}", attempt, e.getMessage());
            } catch (RuntimeException e) {
                draft.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("知识点抽取调用模型失败", e);
                return null;
            }
        }
        return null;
    }

    /** 原始输出和模型名记最后一次的，token 累加，统计的是实际费用。 */
    private static void record(ExtractionDraft draft, LlmResult<?> result) {
        draft.setRawOutput(result.rawText());
        draft.setModel(result.model());
        draft.setPromptTokens(add(draft.getPromptTokens(), result.promptTokens()));
        draft.setCompletionTokens(add(draft.getCompletionTokens(), result.completionTokens()));
    }

    private static Integer add(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + b;
    }

    /** 按用户勾选的题号筛选，保持原文顺序。不传题号时为全部。 */
    static List<SourceQuestion> select(List<SourceQuestion> all, List<Integer> questionIds) {
        if (questionIds == null) {
            return all;
        }
        if (questionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少选择一道题");
        }
        Set<Integer> wanted = new LinkedHashSet<>(questionIds);
        Set<Integer> unknown = new TreeSet<>(wanted);
        all.forEach(q -> unknown.remove(q.id()));
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "题号不存在：" + unknown);
        }
        return all.stream().filter(q -> wanted.contains(q.id())).toList();
    }

    /**
     * 转成抽取 prompt v5 的输入。题号沿用 source 中的题号；追问拼在同一行，
     * 如“说说 HashMap 的原理？（追问：为什么容量是 2 的幂？；1.8 为什么引入红黑树？）”。
     * 输入格式是一行一题，所以题目中的换行统一换成空格。
     */
    static ExtractionQuestion toExtractionQuestion(SourceQuestion q) {
        String text = oneLine(q.text());
        if (q.followUps() != null && !q.followUps().isEmpty()) {
            text += "（追问：" + q.followUps().stream().map(ExtractionDraftService::oneLine)
                    .collect(Collectors.joining("；")) + "）";
        }
        return new ExtractionQuestion(q.id(), text);
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private List<ExtractionDraftItem> toItems(ExtractionResult result) {
        return result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(ExtractedKnowledgePoint::order))
                .map(kp -> {
                    ExtractionDraftItem item = new ExtractionDraftItem();
                    item.setUserId(currentUser.id());
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

    private static DraftView toView(ExtractionDraft draft, List<ExtractionDraftItem> items) {
        List<DraftItemView> views = items.stream()
                .map(item -> new DraftItemView(item.getId(), item.getItemOrder(), item.getTitle(), item.getSummary(),
                        item.getType(), Arrays.stream(item.getSourceQuestionIds().split(","))
                        .map(Integer::valueOf).toList()))
                .toList();
        return new DraftView(draft.getId(), draft.getSourceId(), draft.getStatus(), views);
    }
}
