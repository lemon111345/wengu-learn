package com.wengu.llm.service;

import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmStatusView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 最近一次模型调用的结果（#27，决策 032），设置页的 AI 模型卡片和头像上的红点用它。
 * <p>
 * 存在内存中，只记最近一次：重启后为空，显示“还没有调用过”。M1 中所有用户共用一套模型配置
 * （环境变量 WENGU_LLM_*），状态也是全局的。不为了查状态而真实调用模型。
 */
@Component
public class LlmStatusTracker {

    private final AtomicReference<LlmStatusView.LastCall> lastCall = new AtomicReference<>();
    private final String model;
    private final Clock clock;

    public LlmStatusTracker(@Value("${spring.ai.openai.chat.options.model}") String model, Clock clock) {
        this.model = model;
        this.clock = clock;
    }

    /** 模型有回应。输出不合格也算：那是内容的问题，不是模型连不上或配置不对 */
    void success() {
        lastCall.set(new LlmStatusView.LastCall(true, null, null, Instant.now(clock)));
    }

    void failure(ErrorCode code) {
        lastCall.set(new LlmStatusView.LastCall(false, code.name(), code.getMessage(), Instant.now(clock)));
    }

    /** 模型名和最近一次调用的结果；不含服务地址和 Key */
    public LlmStatusView status() {
        return new LlmStatusView(model, lastCall.get());
    }
}
