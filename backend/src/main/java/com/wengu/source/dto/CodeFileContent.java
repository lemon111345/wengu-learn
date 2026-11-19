package com.wengu.source.dto;

/**
 * 源码导入的一个文件，供抽取和学习读取（#90，决策 050）。
 *
 * @param path    统一成 / 分隔后的路径
 * @param content 文件内容，换行已统一为 \n
 */
public record CodeFileContent(String path, String content) {
}
