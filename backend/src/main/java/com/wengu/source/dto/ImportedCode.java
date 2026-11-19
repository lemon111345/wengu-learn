package com.wengu.source.dto;

import com.wengu.source.entity.SourceType;

import java.util.List;

/**
 * 源码导入的结果（#90）。之后用 id 调用 POST /api/drafts 发起抽取。
 *
 * @param id    source 的 id
 * @param name  材料名称
 * @param type  固定为 CODE
 * @param files 保存的文件，按导入顺序
 */
public record ImportedCode(long id, String name, SourceType type, List<FileSummary> files) {

    /**
     * @param path  统一成 / 分隔后的路径
     * @param chars 统一换行后的字符数
     */
    public record FileSummary(String path, int chars) {
    }
}
