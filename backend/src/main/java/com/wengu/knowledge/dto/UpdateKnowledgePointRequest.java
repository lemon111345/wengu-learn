package com.wengu.knowledge.dto;

/**
 * 编辑知识点，只改传了的字段，为 null 的不变（PATCH）。
 * “恢复 AI 版本”也走这里：前端把 AI 的原文填回要点再保存，同样留下一条修改记录（决策 029）。
 *
 * @param title   写成一个问题，去掉首尾空白后不能为空，最长 500 字
 * @param type    类型；改成或改离只收录的类型时，调度状态的处理见 KnowledgePointService.update
 * @param summary 要点，去掉首尾空白后不能为空
 */
public record UpdateKnowledgePointRequest(String title, KnowledgePointType type, String summary) {
}
