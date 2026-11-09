package com.wengu.plan.dto;

import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.review.fsrs.Rating;

/**
 * 今天已完成的一个知识点。今天页把它放进“新学”或“复习”列表，显示对勾和今天的评分（ui.md 5.2）。
 *
 * @param kpId        知识点 id
 * @param title       写成一个问题的标题
 * @param type        知识点类型
 * @param rating      今天的评分；同一个知识点今天评了多次时取最后一次
 * @param firstReview 是否为新学，否则是复习
 * @param sessionId   这次评分对应的学习会话，点开回顾（#82）；没有时为 null
 */
public record CompletedItem(long kpId, String title, KnowledgePointType type, Rating rating, boolean firstReview,
                            Long sessionId) {
}
