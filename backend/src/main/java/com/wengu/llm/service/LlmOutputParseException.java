package com.wengu.llm.service;

import com.wengu.llm.dto.LlmResult;

/**
 * 模型返回的内容无法解析成目标结构（不是合法 JSON、字段对不上，或输出被截断）。
 * 携带原始返回，调用方可以保存下来排查。
 */
public class LlmOutputParseException extends RuntimeException {

    private final LlmResult<?> result;

    public LlmOutputParseException(String message, LlmResult<?> result, Throwable cause) {
        super(message, cause);
        this.result = result;
    }

    /** 原始返回（content 为 null）。 */
    public LlmResult<?> getResult() {
        return result;
    }
}
