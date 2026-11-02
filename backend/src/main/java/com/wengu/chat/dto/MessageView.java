package com.wengu.chat.dto;

import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.MessageRole;

import java.time.Instant;

/**
 * 返回给前端的一条消息。只给模型看的提示（model_note）不返回。
 *
 * @param content 不含标记的内容
 * @param marker  老师回复中的标记：RESTATE 要求完整作答，END 学习结束；没有时为 null
 */
public record MessageView(long id, MessageRole role, String content, Marker marker, Instant createdAt) {
}
