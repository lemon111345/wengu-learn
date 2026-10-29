package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 发起一次抽取。
 *
 * @param sourceId    导入的材料
 * @param questionIds 用户勾选的题号（source 中的题号）；为 null 时抽取全部
 */
public record CreateDraftRequest(Long sourceId, List<Integer> questionIds) {
}
