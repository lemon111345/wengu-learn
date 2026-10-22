package com.wengu.llm.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 大模型调用入口。其他模块（knowledge、chat）只通过本类调用大模型，不直接使用 Spring AI，
 * 以后的降级链和按用户读取模型配置都在这里实现，调用方不用改。
 * <p>
 * 目前使用 application.yml 中的全局配置（来自 WENGU_LLM_* 环境变量）。
 */
@Service
public class LlmService {

    private final ChatClient chatClient;

    /** ChatClient.Builder 由 Spring AI 根据 spring.ai.openai.* 配置自动创建。 */
    public LlmService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** 普通调用：等模型生成完毕后一次性返回完整回复。 */
    public String call(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    /** 流式调用：模型每生成一段就推送一段，供 SSE 逐字输出。 */
    public Flux<String> stream(String prompt) {
        return chatClient.prompt().user(prompt).stream().content();
    }
}
