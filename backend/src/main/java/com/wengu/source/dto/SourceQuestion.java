package com.wengu.source.dto;

import java.util.List;

/**
 * 导入后的一道题，存在 source.questions 中，也返回给前端预览。
 *
 * @param id        题号，从 1 开始，按原文顺序编排；用户勾选题目、抽取引用来源题都用它
 * @param text      主问题
 * @param followUps 追问，没有时为空列表
 */
public record SourceQuestion(int id, String text, List<String> followUps) {
}
