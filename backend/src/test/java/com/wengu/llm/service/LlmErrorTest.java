package com.wengu.llm.service;

import com.openai.errors.OpenAIIoException;
import com.sun.net.httpserver.HttpServer;
import com.wengu.NoDatabase;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.dto.LlmImage;
import com.wengu.llm.dto.LlmStatusView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型调用失败的分类（#27，决策 032）。用本地的假模型服务（JDK 自带的 HttpServer）模拟各种出错，
 * 同步和流式两种调用都检查，不需要 Key，也不花钱。升级 Spring AI 或 openai-java 后，SDK 抛的异常变了，这里会先失败。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY,
        "spring.ai.openai.api-key=test",
        "spring.ai.openai.timeout=1s",
        "spring.ai.openai.chat.options.model=test-model"})
class LlmErrorTest {

    /** 假服务这次怎么回应：HTTP 状态码，或者 ok、empty、slow、truncated（用量明显少于发送的内容） */
    private static volatile String mode = "ok";

    /** 最近一次收到的请求体 */
    private static volatile String lastBody = "";

    private static final HttpServer server = startServer();

    record Reply(String answer) {
    }

    @DynamicPropertySource
    static void baseUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Autowired
    private LlmService llmService;

    @Autowired
    private LlmStatusTracker statusTracker;

    @BeforeEach
    void reset() {
        mode = "ok";
    }

    private ErrorCode callError() {
        return codeOf(() -> llmService.call("系统", "你好", Reply.class));
    }

    private ErrorCode streamError() {
        return codeOf(() -> llmService.stream("系统", List.of(ChatTurn.user("你好")), null).collectList().block());
    }

    private static ErrorCode codeOf(Runnable call) {
        Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LlmException.class, e -> thrown[0] = e);
        return ((LlmException) thrown[0]).getErrorCode();
    }

    @ParameterizedTest
    @CsvSource({
            "401, LLM_AUTH_FAILED",
            "403, LLM_AUTH_FAILED",
            "404, LLM_MODEL_NOT_FOUND",
            "429, LLM_RATE_LIMITED",
            "500, LLM_UNAVAILABLE",
            "503, LLM_UNAVAILABLE",
            "400, LLM_FAILED",
            "slow, LLM_TIMEOUT",
            "empty, LLM_EMPTY_RESPONSE"
    })
    void 同步和流式调用都按原因分类(String serverMode, ErrorCode expected) {
        mode = serverMode;

        assertThat(callError()).isEqualTo(expected);
        assertThat(streamError()).isEqualTo(expected);
    }

    @Test
    void 连不上时是LLM_UNAVAILABLE() {
        // 实际连接一个没有服务的端口要等系统超时，这里直接构造 SDK 抛出的异常链
        OpenAIIoException io = new OpenAIIoException("Request failed", new ConnectException("Connection refused"));

        assertThat(LlmException.classify(io)).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
        assertThat(LlmException.classify(new CompletionException(io))).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
        assertThat(LlmException.classify(new IllegalStateException("别的问题"))).isEqualTo(ErrorCode.LLM_FAILED);
    }

    private static final List<LlmImage> IMAGES = List.of(new LlmImage(new byte[]{1, 2, 3}, "image/png"));

    private String callWithImages() {
        return llmService.callWithImages("系统", "转写", IMAGES, 0.0);
    }

    @ParameterizedTest
    @CsvSource({
            "400, LLM_IMAGE_UNSUPPORTED",
            "422, LLM_IMAGE_UNSUPPORTED",
            "401, LLM_AUTH_FAILED",
            "500, LLM_UNAVAILABLE",
            "slow, LLM_TIMEOUT"
    })
    void 带图片的调用按原因分类_被拒绝时算模型不支持图片(String serverMode, ErrorCode expected) {
        mode = serverMode;

        assertThat(codeOf(this::callWithImages)).isEqualTo(expected);
    }

    @Test
    void 图片按dataURL发给模型() {
        callWithImages();

        assertThat(lastBody).contains("image_url")
                .contains("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
    }

    @Test
    void 模型不支持图片不算模型出错() {
        callWithImages();

        mode = "400";
        codeOf(this::callWithImages);

        assertThat(statusTracker.status().lastCall().ok()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "你好, 2",
            "你好，世界。, 6",
            "abcdefg, 2",
            "你好abcdefg, 4",
            "'', 0"
    })
    void 估算token_中日韩字符算1个_其余按3点5个字符算1个(String text, long expected) {
        assertThat(LlmService.estimateTokens(text)).isEqualTo(expected);
    }

    /** 数字来自 qwen2.5:7b、上下文 16384 的实测：估算约 2 万时，正常的实际值是 1.6 万，截断后固定为 8194（决策 045） */
    @ParameterizedTest
    @CsvSource({
            "16259, false",
            "12001, false",
            "11999, true",
            "8194, true"
    })
    void 实际token明显少于估算时算截断(int promptTokens, boolean truncated) {
        String twentyThousand = "题".repeat(20000);

        assertThat(LlmService.promptTruncated("", twentyThousand, promptTokens)).isEqualTo(truncated);
    }

    @Test
    void 服务商没有返回用量时不判断截断() {
        String longText = "题".repeat(20000);

        assertThat(LlmService.promptTruncated("", longText, null)).isFalse();
        assertThat(LlmService.promptTruncated("", longText, 0)).isFalse();
    }

    @Test
    void 提示词被截断时给出明确原因_不算模型出错() {
        assertThat(llmService.call("系统", "你好", Reply.class).content()).isEqualTo(new Reply("hi"));

        mode = "truncated";

        assertThat(callError()).isEqualTo(ErrorCode.LLM_CONTEXT_EXCEEDED);
        assertThat(statusTracker.status().lastCall().ok()).isTrue();
    }

    @Test
    void 记下最近一次调用的结果() {
        mode = "401";
        callError();
        LlmStatusView failed = statusTracker.status();
        assertThat(failed.model()).isEqualTo("test-model");
        assertThat(failed.lastCall().ok()).isFalse();
        assertThat(failed.lastCall().code()).isEqualTo("LLM_AUTH_FAILED");
        assertThat(failed.lastCall().message()).isEqualTo(ErrorCode.LLM_AUTH_FAILED.getMessage());

        mode = "ok";
        assertThat(llmService.call("系统", "你好", Reply.class).content()).isEqualTo(new Reply("hi"));
        assertThat(statusTracker.status().lastCall().ok()).isTrue();

        mode = "empty";
        streamError();
        assertThat(statusTracker.status().lastCall().code()).isEqualTo("LLM_EMPTY_RESPONSE");

        mode = "ok";
        assertThat(llmService.stream("系统", List.of(ChatTurn.user("你好")), null).collectList().block())
                .containsExactly("hi");
        assertThat(statusTracker.status().lastCall().ok()).isTrue();
    }

    /** 假的 OpenAI 兼容服务 */
    private static HttpServer startServer() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastBody = body;
                boolean stream = body.contains("\"stream\":true");
                String current = mode;
                int status = 200;
                String response;
                if (current.chars().allMatch(Character::isDigit)) {
                    status = Integer.parseInt(current);
                    response = "{\"error\":{\"message\":\"fake " + current + "\",\"type\":\"test\"}}";
                } else {
                    if (current.equals("slow")) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    String text = current.equals("empty") ? "" : "hi";
                    String json = "{\\\"answer\\\":\\\"hi\\\"}";
                    response = stream
                            ? "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            : "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                            + (text.isEmpty() ? "" : json) + "\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":" + (current.equals("truncated") ? 1 : 100000)
                            + ",\"completion_tokens\":1,\"total_tokens\":2}}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status == 200 && stream ? "text/event-stream" : "application/json");
                try {
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // 客户端已经因为超时断开
                }
                exchange.close();
            });
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
