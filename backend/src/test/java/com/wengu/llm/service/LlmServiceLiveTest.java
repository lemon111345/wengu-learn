package com.wengu.llm.service;

import com.wengu.NoDatabase;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.dto.LlmResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实连测试：真实调用大模型，会产生费用。默认跳过，需要显式开启：
 * <pre>
 * .\mvnw.cmd test -Dtest=LlmServiceLiveTest -Dllm.live=true
 * </pre>
 * 运行前需设置环境变量 WENGU_LLM_API_KEY（可选 WENGU_LLM_BASE_URL、WENGU_LLM_MODEL），见 README。
 * <p>
 * 不用“检测到 Key 就运行”：全局设置了 Key 时，每次 mvnw test 都会真实调用模型。
 */
@SpringBootTest(properties = NoDatabase.PROPERTY)
@EnabledIfSystemProperty(named = "llm.live", matches = "true")
class LlmServiceLiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private LlmService llmService;

    @Test
    void 普通调用返回完整回复() {
        String reply = llmService.call("用一句话解释什么是间隔重复。");

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复为空");
        System.out.println("[普通调用] " + reply);
    }

    @Test
    void 流式调用分多段返回() {
        List<String> chunks = llmService.stream("从 1 数到 20，用中文逗号分隔，不要其他内容。")
                .collectList()
                .block(TIMEOUT);

        assertNotNull(chunks);
        // 只收到一段说明是一次性返回的，不是真正的流式
        assertTrue(chunks.size() > 1, "只收到 " + chunks.size() + " 段，不是流式返回");
        String reply = String.join("", chunks);
        assertFalse(reply.isBlank(), "回复为空");
        System.out.println("[流式调用] 共 " + chunks.size() + " 段: " + reply);
    }

    @Test
    void 多轮调用能看到之前的对话() {
        // 数字只出现在第一轮，模型能答出来，说明历史消息确实发过去了
        List<ChatTurn> history = List.of(
                ChatTurn.user("请记住这个数字：4721。只回复“好的”。"),
                ChatTurn.assistant("好的"),
                ChatTurn.user("我刚才让你记住的数字是多少？只回复数字。"));

        String reply = String.join("", llmService.stream("你是一个简洁的助手。", history, 0.2)
                .collectList()
                .block(TIMEOUT));

        assertTrue(reply.contains("4721"), "没有答出第一轮的数字：" + reply);
        System.out.println("[多轮调用] " + reply);
    }

    record Language(String name, int releaseYear, List<String> keywords) {
    }

    @Test
    void 结构化调用解析为对象() {
        LlmResult<Language> result = llmService.call(
                "你是一个只输出 JSON 的助手。",
                "介绍 Java 语言：名称、首次发布年份、三个关键词。",
                Language.class);

        assertNotNull(result.content(), "没有解析出对象");
        assertEquals(1995, result.content().releaseYear());
        assertFalse(result.content().keywords().isEmpty(), "关键词为空");
        assertFalse(result.truncated(), "输出被截断");
        System.out.println("[结构化调用] " + result.content() + "，模型 " + result.model()
                + "，token " + result.promptTokens() + " + " + result.completionTokens());
    }
}
