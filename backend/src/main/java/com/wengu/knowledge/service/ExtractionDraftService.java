package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.BatchView;
import com.wengu.knowledge.dto.ConfirmDraftRequest;
import com.wengu.knowledge.dto.CoveredView;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftSummary;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ResolvedCovered;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.DraftStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.entity.ItemDecision;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 从导入的材料中抽取知识点，结果存成草稿，用户确认后生成知识点。表结构见决策 012，异步与分批见决策 013，
 * 确认见决策 015。
 * <p>
 * 这里只处理请求：建草稿和批次、查询、重试、确认。调用模型由 {@link ExtractionWorker} 在后台执行，
 * 前端轮询 {@link #get} 查看进度。
 */
@Service
public class ExtractionDraftService {

    private final SourceService sourceService;
    private final ExtractionWorker worker;
    private final KnowledgePointService knowledgePointService;
    private final ExtractionDraftMapper draftMapper;
    private final ExtractionBatchMapper batchMapper;
    private final ExtractionDraftItemMapper itemMapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public ExtractionDraftService(SourceService sourceService, ExtractionWorker worker,
                                  KnowledgePointService knowledgePointService,
                                  ExtractionDraftMapper draftMapper, ExtractionBatchMapper batchMapper,
                                  ExtractionDraftItemMapper itemMapper, CurrentUser currentUser, Clock clock,
                                  JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.sourceService = sourceService;
        this.worker = worker;
        this.knowledgePointService = knowledgePointService;
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
        return toView(draft, batches, orderedItems(batches));
    }

    /** 草稿列表最多返回几份。M1 不做分页，最近的几份足够找回刚才在处理的草稿。 */
    static final int LIST_LIMIT = 20;

    /**
     * 当前用户最近的草稿，新的在前，最多 {@link #LIST_LIMIT} 份。
     * 一共三次查询：草稿、这些草稿的全部批次（用来推算状态）、材料名称。
     */
    public List<DraftSummary> list() {
        List<ExtractionDraft> drafts = draftMapper.selectList(new LambdaQueryWrapper<ExtractionDraft>()
                .eq(ExtractionDraft::getUserId, currentUser.id())
                .orderByDesc(ExtractionDraft::getId)
                .last("LIMIT " + LIST_LIMIT));
        if (drafts.isEmpty()) {
            return List.of();
        }
        List<Long> draftIds = drafts.stream().map(ExtractionDraft::getId).toList();
        Map<Long, List<ExtractionBatch>> batchesByDraft = batchMapper.selectList(
                        new LambdaQueryWrapper<ExtractionBatch>()
                                .in(ExtractionBatch::getDraftId, draftIds)
                                .orderByAsc(ExtractionBatch::getBatchNo)
                                .orderByAsc(ExtractionBatch::getId))
                .stream()
                .collect(Collectors.groupingBy(ExtractionBatch::getDraftId));
        Map<Long, String> sourceNames = sourceService.getNames(
                drafts.stream().map(ExtractionDraft::getSourceId).collect(Collectors.toSet()));

        return drafts.stream().map(draft -> {
            DraftStatus status = draft.getConfirmedAt() != null ? DraftStatus.CONFIRMED
                    : statusOf(latestPerBatchNo(batchesByDraft.getOrDefault(draft.getId(), List.of())));
            return new DraftSummary(draft.getId(), draft.getSourceId(), sourceNames.get(draft.getSourceId()),
                    status, draft.getCreatedAt());
        }).toList();
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
            if (lock(draftId).getConfirmedAt() != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "草稿已经确认，不能再重试");
            }
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

    /**
     * 确认草稿（决策 015）：勾选的条目按草稿中的顺序生成知识点，关联到这份材料，参与调度的类型初始化 card_state；
     * covered 中引用的已有知识点也关联到这份材料；每个条目记下 ACCEPTED 或 REJECTED。全部在一个事务中完成。
     * <p>
     * 只有所有批次都成功（READY）的草稿才能确认：有批次失败时先重试，否则失败批次中的题会悄悄地没有被抽取。
     * 全部取消勾选也可以确认，等于“这份草稿处理完了，一个都不要”。
     *
     * @throws BusinessException 已经确认过、或者还不是 READY 时为 CONFLICT；取消的条目不属于这份草稿时为 BAD_REQUEST
     */
    public DraftView confirm(long draftId, ConfirmDraftRequest request) {
        ExtractionDraft draft = find(draftId);
        Set<Long> rejected = request == null || request.rejectedItemIds() == null ? Set.of()
                : new HashSet<>(request.rejectedItemIds());
        Instant now = Instant.now(clock);
        transactionTemplate.executeWithoutResult(status -> {
            if (lock(draftId).getConfirmedAt() != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "草稿已经确认过");
            }
            List<ExtractionBatch> batches = latestBatches(draftId);
            if (statusOf(batches) != DraftStatus.READY) {
                throw new BusinessException(ErrorCode.CONFLICT, "草稿还没有全部抽取成功，有失败的批次请先重试");
            }
            List<ExtractionDraftItem> items = orderedItems(batches);
            Set<Long> unknown = new TreeSet<>(rejected);
            items.forEach(item -> unknown.remove(item.getId()));
            if (!unknown.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "条目不存在：" + unknown);
            }

            List<ExtractionDraftItem> accepted = items.stream().filter(item -> !rejected.contains(item.getId())).toList();
            knowledgePointService.create(draft.getUserId(), draft.getSourceId(), accepted.stream()
                    .map(item -> new KnowledgePointService.NewKnowledgePoint(
                            item.getType(), item.getTitle(), item.getSummary(), item.getId()))
                    .toList());
            if (draft.getSourceId() != null) {
                knowledgePointService.linkToSource(draft.getUserId(), draft.getSourceId(), coveredKnowledgePoints(batches));
            }
            markDecision(accepted.stream().map(ExtractionDraftItem::getId).toList(), ItemDecision.ACCEPTED);
            markDecision(List.copyOf(rejected), ItemDecision.REJECTED);
            draftMapper.update(new LambdaUpdateWrapper<ExtractionDraft>()
                    .eq(ExtractionDraft::getId, draftId)
                    .set(ExtractionDraft::getConfirmedAt, now));
        });
        return get(draftId);
    }

    /** 锁住草稿这一行：同一份草稿的重试、确认请求依次执行。 */
    private ExtractionDraft lock(long draftId) {
        return draftMapper.selectOne(new LambdaQueryWrapper<ExtractionDraft>()
                .eq(ExtractionDraft::getId, draftId)
                .last("FOR UPDATE"));
    }

    /** 成功批次的条目，按批号、批内顺序排列，就是草稿中显示的顺序。 */
    private List<ExtractionDraftItem> orderedItems(List<ExtractionBatch> batches) {
        List<Long> readyIds = batches.stream()
                .filter(b -> b.getStatus() == BatchStatus.READY)
                .map(ExtractionBatch::getId)
                .toList();
        if (readyIds.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ExtractionDraftItem>> byBatch = itemMapper.selectList(new LambdaQueryWrapper<ExtractionDraftItem>()
                        .in(ExtractionDraftItem::getBatchId, readyIds)
                        .orderByAsc(ExtractionDraftItem::getItemOrder))
                .stream()
                .collect(Collectors.groupingBy(ExtractionDraftItem::getBatchId));
        return readyIds.stream().flatMap(id -> byBatch.getOrDefault(id, List.of()).stream()).toList();
    }

    /** 成功批次的 covered 中引用的已有知识点。引用同一草稿中其他条目的，条目本身会生成知识点，不用另外关联。 */
    private Set<Long> coveredKnowledgePoints(List<ExtractionBatch> batches) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ExtractionBatch batch : batches) {
            if (batch.getStatus() == BatchStatus.READY && batch.getCovered() != null) {
                for (ResolvedCovered c : jsonMapper.readValue(batch.getCovered(), ResolvedCovered[].class)) {
                    ids.addAll(c.knowledgePointIds());
                }
            }
        }
        return ids;
    }

    private void markDecision(List<Long> itemIds, ItemDecision decision) {
        if (itemIds.isEmpty()) {
            return;
        }
        itemMapper.update(new LambdaUpdateWrapper<ExtractionDraftItem>()
                .in(ExtractionDraftItem::getId, itemIds)
                .set(ExtractionDraftItem::getDecision, decision));
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
        return latestPerBatchNo(batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId)
                .orderByAsc(ExtractionBatch::getBatchNo)
                .orderByAsc(ExtractionBatch::getId)));
    }

    /** all 须按批号、id 排好；同一批号后出现的行覆盖先出现的，结果就是每批最新的一行。 */
    private static List<ExtractionBatch> latestPerBatchNo(List<ExtractionBatch> all) {
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

    /**
     * items 已按批号、批内顺序排好（{@link #orderedItems}），order 在整份草稿内重新从 1 连续编号。
     * covered 只取成功的批次，按批号排列。确认之后状态为 CONFIRMED，否则由批次推算。
     */
    private DraftView toView(ExtractionDraft draft, List<ExtractionBatch> batches, List<ExtractionDraftItem> items) {
        List<BatchView> batchViews = new ArrayList<>();
        List<CoveredView> coveredViews = new ArrayList<>();
        for (ExtractionBatch batch : batches) {
            if (batch.getStatus() == BatchStatus.READY && batch.getCovered() != null) {
                for (ResolvedCovered c : jsonMapper.readValue(batch.getCovered(), ResolvedCovered[].class)) {
                    coveredViews.add(new CoveredView(c.questionId(), c.titles()));
                }
            }
            List<Integer> questionIds = Arrays.stream(
                            jsonMapper.readValue(batch.getQuestions(), ExtractionQuestion[].class))
                    .map(ExtractionQuestion::id)
                    .toList();
            batchViews.add(new BatchView(batch.getBatchNo(), batch.getStatus(), questionIds));
        }
        List<DraftItemView> itemViews = new ArrayList<>();
        for (ExtractionDraftItem item : items) {
            itemViews.add(new DraftItemView(item.getId(), itemViews.size() + 1, item.getTitle(), item.getSummary(),
                    item.getType(), Arrays.stream(item.getSourceQuestionIds().split(",")).map(Integer::valueOf).toList(),
                    item.getDecision()));
        }
        DraftStatus status = draft.getConfirmedAt() != null ? DraftStatus.CONFIRMED : statusOf(batches);
        return new DraftView(draft.getId(), draft.getSourceId(), status, batchViews, itemViews, coveredViews);
    }
}
