package com.wengu.knowledge.dto;

/**
 * 知识库列表中的一行（ui.md 5.9）。
 *
 * @param nextReview 下次复习；只有学过的（按稳定性分档的四种）才有，待学、暂停、只收录为 null
 */
public record LibraryItem(long id, String title, KnowledgePointType type, Mastery mastery, NextReview nextReview) {
}
