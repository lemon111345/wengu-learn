package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 草稿中的一个知识点。
 *
 * @param sourceQuestionIds 来源题号，对应 source 中的题号
 */
public record DraftItemView(long id, int order, String title, String summary, KnowledgePointType type,
                            List<Integer> sourceQuestionIds) {
}
