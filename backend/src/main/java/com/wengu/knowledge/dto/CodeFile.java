package com.wengu.knowledge.dto;

/**
 * 学习源码知识点时发给模型的一个文件（#90）。knowledge 自己的类型：chat 不依赖 source，不能直接用 source 的类型。
 *
 * @param path    导入时的路径
 * @param content 导入时的文件内容（快照）
 */
public record CodeFile(String path, String content) {
}
