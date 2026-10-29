package com.wengu.llm.service;

import com.sun.net.httpserver.HttpServer;
import com.wengu.NoDatabase;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.dto.LlmResult;
import okhttp3.Interceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用本地的假模型服务（JDK 自带的 HttpServer）检查每次请求实际生效的超时和重试，不需要 Key，也不花钱。
 * <p>
 * 背景见决策 005 修订：Spring AI 2.0.1 中只配 spring.ai.openai.timeout 不起作用，每次请求实际都是 60 秒；
 * SDK 默认还会把超时的请求悄悄重发 3 次。升级 Spring AI 后如果这些行为变了，这里会先失败。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY,
        "spring.ai.openai.api-key=test",
        "spring.ai.openai.timeout=2s",
        "spring.ai.openai.chat.options.model=test-model"})
@Import(LlmServiceTimeoutTest.RecordTimeouts.class)
class LlmServiceTimeoutTest {

    /** 每次请求实际生效的 okhttp 超时，单位毫秒：[call, read] */
    private static final List<List<Integer>> timeouts = new CopyOnWriteArrayList<>();

    /** 服务端收到的请求体 */
    private static final List<String> requests = new CopyOnWriteArrayList<>();

    private static volatile long delayMillis;

    private static final HttpServer server = startServer();

    record Reply(String answer) {
    }

    @TestConfiguration
    static class RecordTimeouts {

        @Bean
        OpenAiHttpClientBuilderCustomizer recordTimeouts() {
            return builder -> builder.interceptor((Interceptor) chain -> {
                timeouts.add(List.of((int) (chain.call().timeout().timeoutNanos() / 1_000_000), chain.readTimeoutMillis()));
                return chain.proceed(chain.request());
            });
        }
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

    @BeforeEach
    void reset() {
        timeouts.clear();
        requests.clear();
        delayMillis = 0;
    }

    @Test
    void 结构化调用使用配置的超时且保留默认模型() {
        LlmResult<Reply> result = llmService.call("系统", "你好", Reply.class, 0.2);

        assertThat(result.content()).isEqualTo(new Reply("hi"));
        assertThat(timeouts).containsExactly(List.of(2000, 2000));
        assertThat(requests.getFirst()).contains("\"model\":\"test-model\"").contains("\"temperature\":0.2");
    }

    @Test
    void 流式调用使用配置的超时() {
        String reply = String.join("", llmService.stream("系统", List.of(ChatTurn.user("你好")), 0.7)
                .collectList()
                .block());

        assertThat(reply).isEqualTo("hi");
        assertThat(timeouts).containsExactly(List.of(2000, 2000));
    }

    @Test
    void 超时的请求只发一次() {
        delayMillis = 3000;

        assertThatThrownBy(() -> llmService.call("你好")).isInstanceOf(RuntimeException.class);
        assertThat(requests).hasSize(1);
    }

    /** 假的 OpenAI 兼容服务：普通请求返回一段 JSON，流式请求返回 SSE。 */
    private static HttpServer startServer() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                boolean stream = body.contains("\"stream\":true");
                byte[] response = (stream ? """
                        data: {"id":"x","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """ : """
                        {"id":"x","object":"chat.completion","created":1,"model":"test-model",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"answer\\":\\"hi\\"}"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
                try {
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
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
