package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 合并知识点（#25，决策 029）：保留主知识点的标题、要点和复习进度，其他的来源并到主知识点上。
 *
 * @param primaryId 主知识点
 * @param mergedIds 被合并的知识点，不能为空，不能包含主知识点
 */
public record MergeKnowledgePointsRequest(Long primaryId, List<Long> mergedIds) {
}
