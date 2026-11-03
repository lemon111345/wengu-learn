package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 知识点详情中的一个来源：材料名称和题号（ui.md 5.9）。
 *
 * @param questionIds 抽取出这个知识点（或并进来的知识点）的题号，从小到大；
 *                    去重时被补关联的材料（决策 014）没有记下题号，为空列表
 */
public record SourceRef(long sourceId, String name, List<Integer> questionIds) {
}
