package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识点。目前只有抽取去重要用的查询；确认草稿时写入知识点由 #17 添加。
 */
@Service
public class KnowledgePointService {

    private final KnowledgePointMapper knowledgePointMapper;

    public KnowledgePointService(KnowledgePointMapper knowledgePointMapper) {
        this.knowledgePointMapper = knowledgePointMapper;
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
}
