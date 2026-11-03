package com.wengu.llm.controller;

import com.wengu.common.result.Result;
import com.wengu.llm.dto.LlmStatusView;
import com.wengu.llm.service.LlmStatusTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型状态（#27，决策 032）：设置页的 AI 模型卡片和头像上的红点用它。要求登录，但和当前用户无关：
 * M1 中所有用户共用一套模型配置。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmStatusController {

    private final LlmStatusTracker statusTracker;

    public LlmStatusController(LlmStatusTracker statusTracker) {
        this.statusTracker = statusTracker;
    }

    /** 模型名和最近一次调用的结果；不返回服务地址和 Key，也不为此真实调用模型 */
    @GetMapping("/status")
    public Result<LlmStatusView> status() {
        return Result.ok(statusTracker.status());
    }
}
