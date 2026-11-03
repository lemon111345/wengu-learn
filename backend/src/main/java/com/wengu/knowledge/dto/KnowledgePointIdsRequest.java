package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 批量操作选中的知识点：暂停、恢复、删除、撤销删除（#25）。
 *
 * @param ids 知识点 id，不能为空；重复的 id 按一个算
 */
public record KnowledgePointIdsRequest(List<Long> ids) {
}
