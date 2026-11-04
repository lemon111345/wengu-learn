package com.wengu.source.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 模型根据讲解资料整理出的一道题（#26，决策 037）。
 * <p>
 * 字段顺序用 @JsonPropertyOrder 固定（见 {@link SplitQuestion}）：section 放第一个，让模型先定位到资料中的哪一节再出题。
 *
 * @param section   依据资料中的哪一节，原样写出那一节的标题；没有标题时为“无”。用来核对题目确实出自资料
 * @param text      主问题
 * @param followUps 追问，没有时为空列表
 */
@JsonPropertyOrder({"section", "text", "followUps"})
public record GeneratedQuestion(String section, String text, List<String> followUps) {
}
