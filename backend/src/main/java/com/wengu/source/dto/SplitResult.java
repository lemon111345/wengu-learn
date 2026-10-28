package com.wengu.source.dto;

import java.util.List;

/**
 * 一次切分的结果，也是要求模型返回的 JSON 结构的根对象。
 *
 * @param questions 按原文顺序排列的题目
 */
public record SplitResult(List<SplitQuestion> questions) {
}
