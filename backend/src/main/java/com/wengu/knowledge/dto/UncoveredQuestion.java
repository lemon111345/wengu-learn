package com.wengu.knowledge.dto;

/**
 * 没拆出知识点的题：发给了模型，但既没有条目引用它，也没有标为已有（covered）。
 * 可能是被合并进了别的知识点，也可能是漏了；确认页提示用户，可以单独再抽（ui.md 5.8）。
 *
 * @param questionId 题号，对应 source 中的题号
 * @param text       题目原文（发给模型的那一份）
 */
public record UncoveredQuestion(int questionId, String text) {
}
