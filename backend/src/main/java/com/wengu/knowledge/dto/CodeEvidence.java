package com.wengu.knowledge.dto;

/**
 * 源码知识点的一处代码依据（#90，决策 050）。
 *
 * @param file   导入时的文件路径
 * @param symbol 类名#方法名，涉及整个类时只写类名
 */
public record CodeEvidence(String file, String symbol) {
}
