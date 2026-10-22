package com.wengu.llm.service;

import com.wengu.llm.dto.LlmResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
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

    /**
     * 结构化调用：要求模型按 type 对应的 JSON 结构回复，并解析成对象。
     * <p>
     * JSON 格式要求由 Spring AI 根据 type 自动生成，追加在 user 消息末尾。
     * 文本按原样发送，不做模板渲染，其中的花括号不需要转义。
     *
     * @throws LlmOutputParseException 返回内容无法解析时抛出，携带原始返回
     */
    public <T> LlmResult<T> call(String system, String user, Class<T> type) {
        return call(system, user, type, null);
    }

    /**
     * 结构化调用，并指定本次请求的 temperature。只影响这一次请求，不改变全局配置。
     *
     * @param temperature 为 null 时不设置，使用服务商的默认值
     */
    public <T> LlmResult<T> call(String system, String user, Class<T> type, Double temperature) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(system)
                .user(user + "\n\n" + converter.getFormat());
        if (temperature != null) {
            // 传入的选项会叠加在 application.yml 的默认选项（如 model）之上
            request = request.options(ChatOptions.builder().temperature(temperature));
        }
        ChatResponse response = request.call().chatResponse();
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("模型没有返回结果");
        }

        Generation generation = response.getResult();
        Usage usage = response.getMetadata().getUsage();
        LlmResult<T> raw = new LlmResult<>(null,
                generation.getOutput().getText(),
                response.getMetadata().getModel(),
                generation.getMetadata().getFinishReason(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());

        try {
            T content = converter.convert(raw.rawText() == null ? "" : raw.rawText());
            return new LlmResult<>(content, raw.rawText(), raw.model(), raw.finishReason(),
                    raw.promptTokens(), raw.completionTokens());
        } catch (RuntimeException e) {
            String reason = raw.truncated() ? "输出达到长度上限被截断" : "返回内容不符合要求的 JSON 结构";
            throw new LlmOutputParseException(reason, raw, e);
        }
    }
}
