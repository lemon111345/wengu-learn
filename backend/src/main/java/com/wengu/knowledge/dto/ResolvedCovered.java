package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 模型给出的 covered 换回 id 之后的结果，存进 extraction_batch.covered（决策 014）。
 * #17 确认草稿时，要给这里的已有知识点补一条和新资料的关联（kp_source）。
 *
 * @param questionId        被覆盖的题号
 * @param knowledgePointIds 覆盖它的已有知识点
 * @param draftItemIds      覆盖它的同一草稿中其他批次的条目
 * @param titles            抽取当时这些知识点和条目的标题快照，按模型引用的顺序
 * @param reason            模型的判断理由
 */
public record ResolvedCovered(int questionId, List<Long> knowledgePointIds, List<Long> draftItemIds,
                              List<String> titles, String reason) {
}
