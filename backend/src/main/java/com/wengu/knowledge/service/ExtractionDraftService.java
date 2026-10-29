package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.BatchView;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.service.SourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 从导入的材料中抽取知识点，结果存成草稿，等用户确认（#17）。表结构见决策 012，异步与分批见决策 013。
 * <p>
 * 这里只处理请求：建草稿和批次、查询、重试。调用模型由 {@link ExtractionWorker} 在后台执行，
 * 前端轮询 {@link #get} 查看进度。
 */
@Service
public class ExtractionDraftService {

    private final SourceService sourceService;
    private final ExtractionWorker worker;
    private final ExtractionDraftMapper draftMapper;
    private final ExtractionBatchMapper batchMapper;
    private final ExtractionDraftItemMapper itemMapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public ExtractionDraftService(SourceService sourceService, ExtractionWorker worker,
                                  ExtractionDraftMapper draftMapper, ExtractionBatchMapper batchMapper,
                                  ExtractionDraftItemMapper itemMapper, CurrentUser currentUser, Clock clock,
                                  JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.sourceService = sourceService;
        this.worker = worker;
        this.draftMapper = draftMapper;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.currentUser = currentUser;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** 每批最多的题数。M0 的抽取实验和 prompt v5 都是按 30 题左右验证的（决策 005）。 */
    static final int BATCH_SIZE = 30;

    /**
     * 发起抽取：建草稿和排队中的批次，交给后台执行，立即返回 RUNNING 的草稿。
     * 超过 {@link #BATCH_SIZE} 题时分成几批，后台按批号顺序执行。
     * <p>
     * 事务提交之后才交给后台，否则后台线程可能读不到刚插入的批次。
     */
    public DraftView create(CreateDraftRequest request) {
        if (request.sourceId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 sourceId");
        }
        List<SourceQuestion> selected = select(sourceService.getQuestions(request.sourceId()), request.questionIds());
        List<ExtractionQuestion> questions = selected.stream().map(ExtractionDraftService::toExtractionQuestion).toList();
        Instant now = Instant.now(clock);

        ExtractionDraft draft = new ExtractionDraft();
        draft.setUserId(currentUser.id());
        draft.setSourceId(request.sourceId());
        draft.setCreatedAt(now);
        List<List<ExtractionQuestion>> batches = partition(questions, BATCH_SIZE);
        transactionTemplate.executeWithoutResult(status -> {
            draftMapper.insert(draft);
            for (int i = 0; i < batches.size(); i++) {
                batchMapper.insert(newBatch(draft, i + 1, jsonMapper.writeValueAsString(batches.get(i)), now));
            }
        });
        worker.submit(draft.getId());
        return get(draft.getId());
    }

    /**
     * 按原文顺序分成每批不超过 maxSize 题，各批题数尽量相等：31 题分成 16 + 15，而不是 30 + 1。
     * 保持原文顺序，是因为相邻的题通常属于同一主题，分在一批里模型才能合并。
     */
    static <T> List<List<T>> partition(List<T> items, int maxSize) {
        int count = (items.size() + maxSize - 1) / maxSize;
        List<List<T>> batches = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < count; i++) {
            // 前 items.size() % count 批各多分一题
            int size = items.size() / count + (i < items.size() % count ? 1 : 0);
            batches.add(items.subList(start, start + size));
            start += size;
        }
        return batches;
    }

    /** 查看草稿。不存在或不属于当前用户时为 NOT_FOUND。 */
    public DraftView get(long draftId) {
        ExtractionDraft draft = find(draftId);
        List<ExtractionBatch> batches = latestBatches(draftId);
        List<Long> readyIds = batches.stream()
                .filter(b -> b.getStatus() == BatchStatus.READY)
                .map(ExtractionBatch::getId)
                .toList();
        List<ExtractionDraftItem> items = readyIds.isEmpty() ? List.of()
                : itemMapper.selectList(new LambdaQueryWrapper<ExtractionDraftItem>()
                .in(ExtractionDraftItem::getBatchId, readyIds)
                .orderByAsc(ExtractionDraftItem::getItemOrder));
        return toView(draft, batches, items);
    }

    /**
     * 重试失败的批次：每个失败的批次新增一行排队，旧行保留（失败数据留给 #9）。
     * <p>
     * 先锁住草稿这一行，两个同时到达的重试请求不会都插入新批次。
     *
     * @throws BusinessException 草稿还在抽取中时为 CONFLICT；没有失败的批次时为 BAD_REQUEST
     */
    public DraftView retry(long draftId) {
        ExtractionDraft draft = find(draftId);
        Instant now = Instant.now(clock);
        transactionTemplate.executeWithoutResult(status -> {
            draftMapper.selectOne(new LambdaQueryWrapper<ExtractionDraft>()
                    .eq(ExtractionDraft::getId, draftId)
                    .last("FOR UPDATE"));
            List<ExtractionBatch> batches = latestBatches(draftId);
            if (statusOf(batches) == DraftStatus.RUNNING) {
                throw new BusinessException(ErrorCode.CONFLICT, "草稿正在抽取中，请等待完成");
            }
            List<ExtractionBatch> failed = batches.stream().filter(b -> b.getStatus() == BatchStatus.FAILED).toList();
            if (failed.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "没有需要重试的批次");
            }
            failed.forEach(b -> batchMapper.insert(newBatch(draft, b.getBatchNo(), b.getQuestions(), now)));
        });
        worker.submit(draftId);
        return get(draftId);
    }

    private ExtractionDraft find(long draftId) {
        ExtractionDraft draft = draftMapper.selectById(draftId);
        if (draft == null || draft.getUserId() != currentUser.id()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在");
        }
        return draft;
    }

    private static ExtractionBatch newBatch(ExtractionDraft draft, int batchNo, String questionsJson, Instant now) {
        ExtractionBatch batch = new ExtractionBatch();
        batch.setUserId(draft.getUserId());
        batch.setDraftId(draft.getId());
        batch.setBatchNo(batchNo);
        batch.setStatus(BatchStatus.PENDING);
        batch.setQuestions(questionsJson);
        batch.setAttempts(0);
        batch.setCreatedAt(now);
        return batch;
    }

    /** 每个批号取最新的一行（重试会新增行），按批号排列。 */
    private List<ExtractionBatch> latestBatches(long draftId) {
        List<ExtractionBatch> all = batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId)
                .orderByAsc(ExtractionBatch::getBatchNo)
                .orderByAsc(ExtractionBatch::getId));
        Map<Integer, ExtractionBatch> latest = new LinkedHashMap<>();
        all.forEach(b -> latest.put(b.getBatchNo(), b));
        return List.copyOf(latest.values());
    }

    private static DraftStatus statusOf(List<ExtractionBatch> batches) {
        return DraftStatus.of(batches.stream().map(ExtractionBatch::getStatus).toList());
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

    /** 条目按批号、批内顺序排列，order 在整份草稿内重新从 1 连续编号。 */
    private DraftView toView(ExtractionDraft draft, List<ExtractionBatch> batches, List<ExtractionDraftItem> items) {
        Map<Long, List<ExtractionDraftItem>> itemsByBatch = items.stream()
                .collect(Collectors.groupingBy(ExtractionDraftItem::getBatchId));
        List<BatchView> batchViews = new ArrayList<>();
        List<DraftItemView> itemViews = new ArrayList<>();
        for (ExtractionBatch batch : batches) {
            List<Integer> questionIds = Arrays.stream(
                            jsonMapper.readValue(batch.getQuestions(), ExtractionQuestion[].class))
                    .map(ExtractionQuestion::id)
                    .toList();
            batchViews.add(new BatchView(batch.getBatchNo(), batch.getStatus(), questionIds));
            for (ExtractionDraftItem item : itemsByBatch.getOrDefault(batch.getId(), List.of())) {
                itemViews.add(new DraftItemView(item.getId(), itemViews.size() + 1, item.getTitle(),
                        item.getSummary(), item.getType(), Arrays.stream(item.getSourceQuestionIds().split(","))
                        .map(Integer::valueOf).toList()));
            }
        }
        return new DraftView(draft.getId(), draft.getSourceId(), statusOf(batches), batchViews, itemViews);
    }
}
