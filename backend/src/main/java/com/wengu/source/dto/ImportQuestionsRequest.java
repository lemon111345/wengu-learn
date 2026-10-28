package com.wengu.source.dto;

/**
 * 导入面试题的请求。
 *
 * @param name    材料名称，如“Redis 高频题”
 * @param content 粘贴的原文，可以带答案
 */
public record ImportQuestionsRequest(String name, String content) {
}
