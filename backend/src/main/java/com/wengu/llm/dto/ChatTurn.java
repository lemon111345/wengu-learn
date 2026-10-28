package com.wengu.llm.dto;

/**
 * 多轮对话中的一条消息。用项目自己的类型表示对话历史，调用方不需要接触 Spring AI 的 Message 类型。
 *
 * @param role    说话的一方
 * @param content 消息内容
 */
public record ChatTurn(Role role, String content) {

    public enum Role {
        /** 用户（学生）说的话。 */
        USER,
        /** 模型的回复。 */
        ASSISTANT
    }

    public static ChatTurn user(String content) {
        return new ChatTurn(Role.USER, content);
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn(Role.ASSISTANT, content);
    }
}
