package com.wengu.knowledge.dto;

import java.util.List;
import java.util.Map;

/**
 * 知识库列表（#50）。不分页：MVP 只有几百个知识点，各掌握程度的数量本来就要全部算一遍。
 *
 * @param total  全部知识点数（删除的不算），即“共 Y 个”
 * @param counts 各掌握程度的数量，只受类型筛选影响，不受掌握程度筛选影响；七种都有，没有的为 0
 * @param items  两种筛选都生效后的知识点，已排好序
 */
public record LibraryView(int total, Map<Mastery, Integer> counts, List<LibraryItem> items) {
}
