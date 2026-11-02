package com.wengu.chat.dto;

/**
 * 学生的一次回答。
 *
 * @param content 回答内容，不能为空，最多 5000 字
 */
public record SendMessageRequest(String content) {
}
