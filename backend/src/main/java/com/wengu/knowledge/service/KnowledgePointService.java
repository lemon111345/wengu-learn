package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.entity.KpSource;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.service.ReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识点：创建、关联材料，以及抽取去重要用的查询。编辑、合并、暂停由 #25 添加。
 */
@Service
public class KnowledgePointService {

    private final KnowledgePointMapper knowledgePointMapper;
    private final KpSourceMapper kpSourceMapper;
    private final ReviewService reviewService;
    private final Clock clock;

    public KnowledgePointService(KnowledgePointMapper knowledgePointMapper, KpSourceMapper kpSourceMapper,
                                 ReviewService reviewService, Clock clock) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.kpSourceMapper = kpSourceMapper;
        this.reviewService = reviewService;
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
