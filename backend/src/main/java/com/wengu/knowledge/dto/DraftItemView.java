package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.ItemDecision;

import java.util.List;

/**
 * 草稿中的一个知识点。
 *
 * @param order             在整份草稿中的学习顺序，从 1 开始
 * @param sourceQuestionIds 来源题号，对应 source 中的题号
 * @param decision          确认时的处理：ACCEPTED 或 REJECTED；草稿还没确认时为 null
 */
public record DraftItemView(long id, int order, String title, String summary, KnowledgePointType type,
                            List<Integer> sourceQuestionIds, ItemDecision decision) {
}
