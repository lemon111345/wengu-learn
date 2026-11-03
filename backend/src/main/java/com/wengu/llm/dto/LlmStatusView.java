package com.wengu.llm.dto;

import java.time.Instant;

/**
 * 模型状态（#27，决策 032）：模型名和最近一次调用的结果。不返回服务地址和 Key。
 *
 * @param model    配置的模型名
 * @param lastCall 最近一次调用；重启后还没有调用过时为 null
 */
public record LlmStatusView(String model, LastCall lastCall) {

    /**
     * @param ok      模型是否有回应
     * @param code    失败时的返回码，如 LLM_AUTH_FAILED；成功时为 null
     * @param message 失败时给用户看的提示；成功时为 null
     * @param at      调用结束的时间（UTC）
     */
    public record LastCall(boolean ok, String code, String message, Instant at) {
    }
}
