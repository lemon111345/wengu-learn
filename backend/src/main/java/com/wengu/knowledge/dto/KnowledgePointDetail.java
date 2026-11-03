package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.KnowledgePointStatus;

import java.util.List;

/**
 * 知识库右侧的知识点详情（ui.md 5.9，#50）。
 *
 * @param status        暂停按钮显示“暂停复习”还是“恢复复习”看它：只收录的类型也可能被暂停，mastery 表达不了
 * @param nextReview    下次复习，规则同列表
 * @param aiSummary     AI 写的要点原文（确认时关联的草稿条目），“恢复 AI 版本”用；不是从草稿来的为 null
 * @param summaryEdited 要点和 AI 原文不同（忽略首尾空白）；没有 AI 原文时为 false
 * @param reviews       学习记录，最近的在前，包括并进来的知识点的
 * @param sources       来源材料，按导入顺序
 */
public record KnowledgePointDetail(long id, String title, KnowledgePointType type, KnowledgePointStatus status,
                                   Mastery mastery, NextReview nextReview, String summary, String aiSummary,
                                   boolean summaryEdited, List<StudyRecord> reviews, List<SourceRef> sources) {
}
