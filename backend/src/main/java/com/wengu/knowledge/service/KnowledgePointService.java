package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.entity.KpSource;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.service.ReviewService;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.service.SourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识点：创建、关联材料，以及抽取去重、每日计划、学习对话要用的查询。编辑、合并、暂停由 #25 添加。
 */
@Service
public class KnowledgePointService {

    private final KnowledgePointMapper knowledgePointMapper;
    private final KpSourceMapper kpSourceMapper;
    private final ExtractionDraftItemMapper draftItemMapper;
    private final ExtractionDraftMapper draftMapper;
    private final ReviewService reviewService;
    private final SourceService sourceService;
    private final Clock clock;

    public KnowledgePointService(KnowledgePointMapper knowledgePointMapper, KpSourceMapper kpSourceMapper,
                                 ExtractionDraftItemMapper draftItemMapper, ExtractionDraftMapper draftMapper,
                                 ReviewService reviewService, SourceService sourceService, Clock clock) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.kpSourceMapper = kpSourceMapper;
        this.draftItemMapper = draftItemMapper;
        this.draftMapper = draftMapper;
        this.reviewService = reviewService;
        this.sourceService = sourceService;
        this.clock = clock;
    }

    /**
     * 一个待创建的知识点。
     *
     * @param draftItemId 来自哪个草稿条目；不是从草稿来的为 null
     */
    public record NewKnowledgePoint(KnowledgePointType type, String title, String summary, Long draftItemId) {
    }

    /**
     * 用户所有知识点的 id 和标题，按学习顺序排列，作为抽取的去重上下文（决策 014）。
     * 暂停的知识点也算：暂停只是暂时不学，知识点还在，再抽一个重复的没有意义。
     * <p>
     * userId 由调用方传入：抽取在后台线程中执行，读不到 CurrentUser（决策 013）。
     */
    public List<DedupContext.Existing> listTitles(long userId) {
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                        .select(KnowledgePoint::getId, KnowledgePoint::getTitle)
                        .eq(KnowledgePoint::getUserId, userId)
                        .orderByAsc(KnowledgePoint::getSortOrder)
                        .orderByAsc(KnowledgePoint::getId))
                .stream()
                .map(kp -> new DedupContext.Existing(kp.getId(), kp.getTitle()))
                .toList();
    }

    /**
     * 按给定顺序创建知识点（决策 015）：
     * <ul>
     *   <li>sort_order 接在用户已有知识点的最大值之后，连续编号，新知识就按这个顺序派发（决策 009）</li>
     *   <li>每个知识点关联到 sourceId（为 null 时不关联，例如以后由学习会话产生的知识点）</li>
     *   <li>参与调度的类型调用 review 初始化 card_state；SYSTEM_DESIGN、EXPERIENCE 不初始化（决策 007）</li>
     * </ul>
     * 在调用方的事务中执行，任何一步失败都整体回滚。
     *
     * @return 新知识点的 id，和 points 的顺序一一对应
     */
    @Transactional
    public List<Long> create(long userId, Long sourceId, List<NewKnowledgePoint> points) {
        int sortOrder = maxSortOrder(userId);
        Instant now = Instant.now(clock);
        List<Long> ids = new ArrayList<>();
        List<Long> scheduled = new ArrayList<>();
        for (NewKnowledgePoint p : points) {
            KnowledgePoint kp = new KnowledgePoint();
            kp.setUserId(userId);
            kp.setType(p.type());
            kp.setTitle(p.title());
            kp.setSummary(p.summary());
            kp.setStatus(KnowledgePointStatus.ACTIVE);
            kp.setSortOrder(++sortOrder);
            kp.setDraftItemId(p.draftItemId());
            kp.setCreatedAt(now);
            kp.setUpdatedAt(now);
            knowledgePointMapper.insert(kp);
            ids.add(kp.getId());
            if (p.type().isScheduled()) {
                scheduled.add(kp.getId());
            }
            if (sourceId != null) {
                kpSourceMapper.insert(KpSource.of(kp.getId(), sourceId, userId));
            }
        }
        if (!scheduled.isEmpty()) {
            reviewService.initCards(userId, scheduled);
        }
        return ids;
    }

    /**
     * 给已有知识点补上与材料的关联，例如新资料中的题被已有知识点覆盖时（决策 014）。
     * 已经关联过的跳过；不属于该用户或已经不存在的知识点跳过。
     */
    @Transactional
    public void linkToSource(long userId, long sourceId, Collection<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return;
        }
        Set<Long> owned = knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                        .select(KnowledgePoint::getId)
                        .eq(KnowledgePoint::getUserId, userId)
                        .in(KnowledgePoint::getId, kpIds))
                .stream().map(KnowledgePoint::getId).collect(Collectors.toSet());
        if (owned.isEmpty()) {
            return;
        }
        Set<Long> linked = kpSourceMapper.selectList(new LambdaQueryWrapper<KpSource>()
                        .eq(KpSource::getSourceId, sourceId)
                        .in(KpSource::getKpId, owned))
                .stream().map(KpSource::getKpId).collect(Collectors.toSet());
        for (Long kpId : owned) {
            if (!linked.contains(kpId)) {
                kpSourceMapper.insert(KpSource.of(kpId, sourceId, userId));
            }
        }
    }

    /**
     * 每日计划第 6 步：从给定的 id 中去掉暂停的，按学习顺序（sort_order、id）排列（决策 002 第 6 条）。
     * 候选 id 来自 review，这里只读 knowledge 自己的表；不属于该用户的 id 直接忽略。
     * <p>
     * 用 IN 查询、在数据库中排序，MVP 只有几百个知识点，足够快；到几千个以后再改。
     */
    public List<KnowledgePointSummary> activeInOrder(long userId, Collection<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return List.of();
        }
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                        .select(KnowledgePoint::getId, KnowledgePoint::getTitle, KnowledgePoint::getType)
                        .eq(KnowledgePoint::getUserId, userId)
                        .eq(KnowledgePoint::getStatus, KnowledgePointStatus.ACTIVE)
                        .in(KnowledgePoint::getId, kpIds)
                        .orderByAsc(KnowledgePoint::getSortOrder)
                        .orderByAsc(KnowledgePoint::getId))
                .stream()
                .map(kp -> new KnowledgePointSummary(kp.getId(), kp.getTitle(), kp.getType()))
                .toList();
    }

    /**
     * 按 id 查标题和类型，供每日计划显示今天已完成的条目（#48）。
     * 暂停的也返回：“今天学过”是已经发生的事，暂停不改变它；以后有了软删除（#25），删除的不返回。
     * 不属于该用户的 id 不在结果中。
     */
    public Map<Long, KnowledgePointSummary> summaries(long userId, Collection<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return Map.of();
        }
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                        .select(KnowledgePoint::getId, KnowledgePoint::getTitle, KnowledgePoint::getType)
                        .eq(KnowledgePoint::getUserId, userId)
                        .in(KnowledgePoint::getId, kpIds))
                .stream()
                .collect(Collectors.toMap(KnowledgePoint::getId,
                        kp -> new KnowledgePointSummary(kp.getId(), kp.getTitle(), kp.getType())));
    }

    /**
     * 学习一个知识点时发给模型的材料：标题、类型、要点、来源面试题（#19）。
     * 来源面试题按“知识点 → 草稿条目 → 草稿的材料”找到，题号来自条目的 source_question_ids。
     * 暂停的知识点也能取：用户从知识库直接打开时照样可以学。
     *
     * @throws BusinessException 知识点不存在或不属于这个用户时为 NOT_FOUND
     */
    public StudyMaterial studyMaterial(long userId, long kpId) {
        KnowledgePoint kp = knowledgePointMapper.selectById(kpId);
        if (kp == null || kp.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识点不存在");
        }
        return new StudyMaterial(kp.getId(), kp.getTitle(), kp.getType(), kp.getSummary(),
                sourceQuestions(userId, kp.getDraftItemId()));
    }

    /** 草稿条目引用的来源题，带上追问；找不到时返回空列表，不影响学习。 */
    private List<String> sourceQuestions(long userId, Long draftItemId) {
        ExtractionDraftItem item = draftItemId == null ? null : draftItemMapper.selectById(draftItemId);
        ExtractionDraft draft = item == null ? null : draftMapper.selectById(item.getDraftId());
        if (draft == null || draft.getSourceId() == null) {
            return List.of();
        }
        List<SourceQuestion> questions;
        try {
            questions = sourceService.getQuestions(userId, draft.getSourceId());
        } catch (BusinessException e) {
            // 材料被删掉了，或者没有题目
            return List.of();
        }
        Set<Integer> ids = Arrays.stream(item.getSourceQuestionIds().split(","))
                .map(String::strip)
                .map(Integer::valueOf)
                .collect(Collectors.toSet());
        return questions.stream()
                .filter(q -> ids.contains(q.id()))
                .map(q -> q.followUps().isEmpty() ? q.text()
                        : q.text() + "（追问：" + String.join("；", q.followUps()) + "）")
                .toList();
    }

    /** 用户的知识点总数，暂停的也算。今天页据此判断是否显示空状态（#49）。 */
    public int count(long userId) {
        return Math.toIntExact(knowledgePointMapper.selectCount(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getUserId, userId)));
    }

    /** 用户已有知识点中最大的 sort_order，没有知识点时为 0。 */
    private int maxSortOrder(long userId) {
        KnowledgePoint last = knowledgePointMapper.selectOne(new LambdaQueryWrapper<KnowledgePoint>()
                .select(KnowledgePoint::getSortOrder)
                .eq(KnowledgePoint::getUserId, userId)
                .orderByDesc(KnowledgePoint::getSortOrder)
                .last("LIMIT 1"));
        return last == null ? 0 : last.getSortOrder();
    }
}
