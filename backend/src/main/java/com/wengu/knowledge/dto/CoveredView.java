package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 返回给前端的“这道题已经有了”，用于在草稿中提示“第 5 题已有：《…》”。
 * 模型判断错了的话，用户能从这里看出来。
 *
 * @param questionId 题号，对应 source 中的题号
 * @param titles     覆盖它的已有知识点或其他批次条目的标题（抽取当时的快照）
 */
public record CoveredView(int questionId, List<String> titles) {
}
