package com.wengu.chat.dto;

import com.wengu.chat.entity.Marker;
import org.springframework.http.codec.ServerSentEvent;

/**
 * 流式回复中推给前端的三种 SSE 事件（决策 025）：一段文字、回复完成、出错。
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /** 一段文字，前端依次拼接显示。标记已经去掉 */
    public record Delta(String text) {
    }

    /**
     * 回复生成完并已存库。
     *
     * @param messageId 存库的消息 id
     * @param marker    RESTATE 要求完整作答，END 学习结束；没有时为 null
     * @param ended     学习对话是否结束（模型输出了【学习结束】，或者回答达到上限），结束后进入评分
     */
    public record Done(long messageId, Marker marker, boolean ended) {
    }

    /** 出错，message 可以直接显示给用户；学生的消息已经存下，点重试会重新生成这条回复 */
    public record Error(String message) {
    }

    public static ServerSentEvent<Object> delta(String text) {
        return ServerSentEvent.<Object>builder(new Delta(text)).event("delta").build();
    }

    public static ServerSentEvent<Object> done(long messageId, Marker marker, boolean ended) {
        return ServerSentEvent.<Object>builder(new Done(messageId, marker, ended)).event("done").build();
    }

    public static ServerSentEvent<Object> error(String message) {
        return ServerSentEvent.<Object>builder(new Error(message)).event("error").build();
    }
}
