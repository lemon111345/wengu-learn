package com.wengu.knowledge.dto;

/**
 * 待抽取知识点的一道题。
 *
 * @param id   题号，模型在 sourceQuestionIds 中引用它
 * @param text 题目原文
 */
public record ExtractionQuestion(int id, String text) {
}
