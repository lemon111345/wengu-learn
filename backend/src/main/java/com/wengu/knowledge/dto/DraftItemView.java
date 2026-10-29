package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 草稿中的一个知识点。
 *
 * @param order             在整份草稿中的学习顺序，从 1 开始
 * @param sourceQuestionIds 来源题号，对应 source 中的题号
 */
public record DraftItemView(long id, int order, String title, String summary, KnowledgePointType type,
                            List<Integer> sourceQuestionIds) {
}
