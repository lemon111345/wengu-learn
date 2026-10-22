package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 模型抽取出的一个知识点。字段名即 JSON 中的字段名，含义见 prompts/knowledge-extraction-*.md。
 *
 * @param order             学习顺序，从 1 开始
 * @param title             写成一个问题的标题
 * @param summary           需要讲清楚的要点
 * @param type              知识点类型
 * @param sourceQuestionIds 来源题号
 */
public record ExtractedKnowledgePoint(int order, String title, String summary, KnowledgePointType type,
                                      List<Integer> sourceQuestionIds) {
}
