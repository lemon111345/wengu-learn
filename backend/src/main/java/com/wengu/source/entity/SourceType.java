package com.wengu.source.entity;

/** 材料类型，按名称存进 source.type。源码、截图等类型由对应的 issue 添加。 */
public enum SourceType {
    /** 题目清单：面试题文本、题库。模型从原文中逐字找出题目（决策 011） */
    QUESTION,
    /** 讲解资料：笔记、技术文章、书的章节。原文中没有现成的题目，由模型根据内容整理出题目（#26，决策 037） */
    DOCUMENT
}
