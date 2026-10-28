package com.wengu.source.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 模型切分出的一道题：主问题和它的追问。主问题和追问算一道题，抽取时一起发给模型。
 * <p>
 * 字段顺序用 @JsonPropertyOrder 固定：Spring AI 生成 JSON Schema 时默认按字母排序，followUps 会排到 text 前面，
 * 模型就会先写追问再写主问题。
 *
 * @param text      主问题，从原文逐字复制
 * @param followUps 追问，从原文逐字复制；没有追问时为空列表
 */
@JsonPropertyOrder({"text", "followUps"})
public record SplitQuestion(String text, List<String> followUps) {
}
