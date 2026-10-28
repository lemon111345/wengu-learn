package com.wengu.source.dto;

import java.util.List;

/**
 * 导入结果，供用户预览切分出的题目。确认后用 id 和勾选的题号发起抽取。
 *
 * @param id        source 的 id
 * @param name      材料名称
 * @param questions 切分出的题目
 */
public record ImportedSource(long id, String name, List<SourceQuestion> questions) {
}
