package com.wengu.llm.service;

import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.dto.LlmImage;
import com.wengu.llm.dto.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 大模型调用入口。其他模块（knowledge、chat）只通过本类调用大模型，不直接使用 Spring AI，
 * 以后的降级链和按用户读取模型配置都在这里实现，调用方不用改。
 * <p>
 * 目前使用 application.yml 中的全局配置（来自 WENGU_LLM_* 环境变量）。
 * <p>
 * 每次请求都显式带上超时（见 {@link #options}）。SDK 的自动重试在 application.yml 中关掉了，
 * 要不要重试由调用方决定（决策 005 修订）。
 * <p>
 * 调用失败时一律抛出 {@link LlmException}（按 Key 不对、超时、空正文等分类，决策 032），
 * 每次调用的结果记到 {@link LlmStatusTracker}。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** 实际 token 数不到估算值的这个比例时，认为提示词被截断了。实测正常 0.73～0.89，截断时不超过 0.41（决策 045） */
    private static final double TRUNCATED_PROMPT_RATIO = 0.6;

    private final ChatClient chatClient;
    private final Duration timeout;
    private final LlmStatusTracker status;

    /** ChatClient.Builder 由 Spring AI 根据 spring.ai.openai.* 配置自动创建。 */
    public LlmService(ChatClient.Builder chatClientBuilder, @Value("${spring.ai.openai.timeout}") Duration timeout,
                      LlmStatusTracker status) {
        this.chatClient = chatClientBuilder.build();
        this.timeout = timeout;
        this.status = status;
    }

    /**
     * 普通调用：等模型生成完毕后一次性返回完整回复。
     *
     * @throws LlmException 调用失败，或者没有返回正文
     */
    public String call(String prompt) {
        String content = guard(() -> chatClient.prompt().user(prompt).options(options(null)).call().content());
        if (content == null || content.isBlank()) {
            throw fail(new LlmException(ErrorCode.LLM_EMPTY_RESPONSE));
        }
        status.success();
        return content;
    }

    /**
     * 带图片的调用，返回模型的回复原文（决策 044）。
     * <p>
     * 服务商拒绝这个请求（400、422）时，多半是模型不支持图片，抛出 {@link ErrorCode#LLM_IMAGE_UNSUPPORTED}。
     * 这不计入模型状态的失败：模型本身没有坏，不该让头像上出现红点。
     *
     * @param images      按顺序发送
     * @param temperature 为 null 时不设置，使用服务商的默认值
     * @throws LlmException 调用失败，或者没有返回正文
     */
    public String callWithImages(String system, String user, List<LlmImage> images, Double temperature) {
        Media[] media = images.stream()
                .map(image -> new Media(MimeType.valueOf(image.mimeType()), new ByteArrayResource(image.data())))
                .toArray(Media[]::new);
        String content;
        try {
            content = chatClient.prompt()
                    .system(system)
                    .user(spec -> spec.text(user).media(media))
                    .options(options(temperature))
                    .call()
                    .content();
        } catch (RuntimeException e) {
            if (LlmException.imagesRejected(e)) {
                log.warn("模型拒绝了带图片的请求: {}", e.toString());
                throw new LlmException(ErrorCode.LLM_IMAGE_UNSUPPORTED, e);
            }
            throw fail(LlmException.from(e));
        }
        if (content == null || content.isBlank()) {
            throw fail(new LlmException(ErrorCode.LLM_EMPTY_RESPONSE));
        }
        status.success();
        return content;
    }

    /** 流式调用：模型每生成一段就推送一段，供 SSE 逐字输出。出错时以 {@link LlmException} 结束 */
    public Flux<String> stream(String prompt) {
        return tracked(chatClient.prompt().user(prompt).options(options(null)).stream().content());
    }

    /**
     * 多轮流式调用：带上 system prompt 和此前的全部对话，模型每生成一段就推送一段。
     * <p>
     * 模型本身不记得之前的对话，每次都要把完整历史发过去，history 的最后一条通常是用户刚说的话。
     *
     * @param temperature 为 null 时不设置，使用服务商的默认值
     */
    public Flux<String> stream(String system, List<ChatTurn> history, Double temperature) {
        List<Message> messages = history.stream().map(LlmService::toMessage).toList();
        return tracked(chatClient.prompt()
                .system(system)
                .messages(messages)
                .options(options(temperature))
                .stream()
                .content());
    }

    /**
     * 给流式调用加上错误分类和状态记录。
     * <p>
     * 流正常结束、但一个字都没有时，SDK 不报错（实测），这里以 {@link ErrorCode#LLM_EMPTY_RESPONSE} 结束。
     * 每次订阅单独计数：defer 让每个订阅者有自己的 received。
     */
    private Flux<String> tracked(Flux<String> source) {
        return Flux.defer(() -> {
            AtomicBoolean received = new AtomicBoolean();
            return source
                    .doOnNext(text -> {
                        if (text != null && !text.isEmpty()) {
                            received.set(true);
                        }
                    })
                    .concatWith(Mono.defer(() -> received.get() ? Mono.<String>empty()
                            : Mono.error(new LlmException(ErrorCode.LLM_EMPTY_RESPONSE))))
                    .doOnComplete(status::success)
                    .onErrorMap(e -> fail(LlmException.from(e)));
        });
    }

    /** 同步调用：SDK 的异常转成 LlmException 并记下 */
    private <T> T guard(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw fail(LlmException.from(e));
        }
    }

    /** 记下失败，返回原异常，供调用处直接 throw */
    private LlmException fail(LlmException e) {
        status.failure(e.getErrorCode());
        // 只记返回码和原始异常的类名、信息：服务商的错误信息里没有 Key，但请求体（用户的对话）不进日志
        log.warn("大模型调用失败: {}, cause={}", e.getErrorCode(), e.getCause() == null ? "-" : e.getCause().toString());
        return e;
    }

    /**
     * 每次请求的选项，叠加在 application.yml 的默认选项（如 model）之上。
     * <p>
     * 超时必须每次都带上：Spring AI 2.0.1 的 OpenAiChatOptions 没设 timeout 时会填入 60 秒，
     * 而每次请求的选项会覆盖客户端上配置的超时，所以只配 spring.ai.openai.timeout 不起作用。
     *
     * @param temperature 为 null 时不设置，使用服务商的默认值
     */
    private OpenAiChatOptions.Builder options(Double temperature) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().timeout(timeout);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder;
    }

    private static Message toMessage(ChatTurn turn) {
        return switch (turn.role()) {
            case USER -> new UserMessage(turn.content());
            case ASSISTANT -> new AssistantMessage(turn.content());
        };
    }

    /**
     * 结构化调用：要求模型按 type 对应的 JSON 结构回复，并解析成对象。
     * <p>
     * JSON 格式要求由 Spring AI 根据 type 自动生成，追加在 user 消息末尾。
     * 文本按原样发送，不做模板渲染，其中的花括号不需要转义。
     *
     * @throws LlmOutputParseException 返回内容无法解析时抛出，携带原始返回
     * @throws LlmException            调用失败、没有返回正文，或者提示词被截断
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
        String fullUser = user + "\n\n" + converter.getFormat();
        ChatResponse response = guard(() -> chatClient.prompt()
                .system(system)
                .user(fullUser)
                .options(options(temperature))
                .call()
                .chatResponse());
        if (response == null || response.getResult() == null) {
            throw fail(new LlmException(ErrorCode.LLM_EMPTY_RESPONSE));
        }

        Generation generation = response.getResult();
        Usage usage = response.getMetadata().getUsage();
        LlmResult<T> raw = new LlmResult<>(null,
                generation.getOutput().getText(),
                response.getMetadata().getModel(),
                generation.getMetadata().getFinishReason(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        // 放在空正文判断之前：提示词残缺时，后面的任何结果都不可信。
        // 和不支持图片一样，模型本身没有坏，不计入模型状态的失败（决策 044、045）
        if (promptTruncated(system, fullUser, raw.promptTokens())) {
            log.warn("提示词疑似被截断: promptTokens={}, 估算={}", raw.promptTokens(),
                    estimateTokens(system) + estimateTokens(fullUser));
            throw new LlmException(ErrorCode.LLM_CONTEXT_EXCEEDED);
        }
        // 正常结束却没有正文：不当作“输出不合格”，否则调用方会重试，而这多半每次都一样（决策 032）。
        // 被截断（length）的空输出仍然算输出问题，交给下面的解析
        if ((raw.rawText() == null || raw.rawText().isBlank()) && !raw.truncated()) {
            throw fail(new LlmException(ErrorCode.LLM_EMPTY_RESPONSE));
        }
        status.success();

        try {
            T content = converter.convert(raw.rawText() == null ? "" : raw.rawText());
            return new LlmResult<>(content, raw.rawText(), raw.model(), raw.finishReason(),
                    raw.promptTokens(), raw.completionTokens());
        } catch (RuntimeException e) {
            String reason = raw.truncated() ? "输出达到长度上限被截断" : "返回内容不符合要求的 JSON 结构";
            throw new LlmOutputParseException(reason, raw, e);
        }
    }

    /**
     * 提示词有没有被截断：模型实际读到的 token 数明显少于发送内容的估算值。
     * <p>
     * Ollama 的 OpenAI 兼容接口在提示词超过上下文时不报错，只是悄悄丢掉一部分（决策 032、045）。
     *
     * @param promptTokens 服务商返回的提示词 token 数，没有返回时不判断
     */
    static boolean promptTruncated(String system, String user, Integer promptTokens) {
        if (promptTokens == null || promptTokens <= 0) {
            return false;
        }
        return promptTokens < TRUNCATED_PROMPT_RATIO * (estimateTokens(system) + estimateTokens(user));
    }

    /** 粗略估算 token 数：中日韩字符和全角符号算 1 个，其余 3.5 个字符算 1 个 */
    static long estimateTokens(String text) {
        long wide = text.codePoints().filter(LlmService::isWide).count();
        long others = text.codePointCount(0, text.length()) - wide;
        return Math.round(wide + others / 3.5);
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x3000 && codePoint <= 0x9FFF) || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }
}
