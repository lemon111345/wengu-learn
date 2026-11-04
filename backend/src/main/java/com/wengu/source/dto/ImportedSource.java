package com.wengu.source.dto;

import com.wengu.source.entity.SourceType;

import java.util.List;

/**
 * 导入结果，供用户预览识别或整理出的题目。确认后用 id 和勾选的题号发起抽取。
 *
 * @param id        source 的 id
 * @param name      材料名称
 * @param type      资料类型（#26 新增）：QUESTION 的题目是从原文中识别的，DOCUMENT 的是根据内容整理的
 * @param questions 题目
 */
public record ImportedSource(long id, String name, SourceType type, List<SourceQuestion> questions) {
}
