package com.wengu.knowledge.dto;

/** 知识库列表的排序（ui.md 5.9 点表头）。 */
public enum LibrarySort {
    /** 学习顺序（sort_order、id），默认；不分方向 */
    ORDER,
    /** 掌握程度：按稳定性，升序是生疏的在前 */
    MASTERY,
    /** 下次复习：按到期时间，升序是早的在前 */
    NEXT_REVIEW
}
