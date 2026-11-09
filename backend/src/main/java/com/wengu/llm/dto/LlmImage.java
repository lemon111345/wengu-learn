package com.wengu.llm.dto;

/**
 * 发给模型的一张图片。用项目自己的类型表示，调用方不需要接触 Spring AI 的类。
 *
 * @param data     图片内容
 * @param mimeType 如 image/png
 */
public record LlmImage(byte[] data, String mimeType) {
}
