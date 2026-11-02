package com.wengu.chat.controller;

import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.llm.service.LlmService;
import com.wengu.user.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.wengu.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学习会话的 HTTP 接口：完整的过滤器链（登录、CSRF）、SSE 的格式、校验失败时返回 JSON。
 * 模型换成模拟对象；各个测试的用户名随机生成，互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @MockitoBean
    private LlmService llmService;

    private MockHttpSession login;
    private long kpId;

    @BeforeEach
    void registerAndAddKnowledgePoint() throws Exception {
        String username = "chat" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(username, "correct-horse-1", null))))
                .andExpect(status().isOk())
                .andReturn();
        login = (MockHttpSession) result.getRequest().getSession(false);
        long userId = jsonMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();

        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle("HashMap 的扩容机制是怎样的？");
        kp.setSummary("要点");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(Instant.now());
        kp.setUpdatedAt(Instant.now());
        knowledgePointMapper.insert(kp);
        kpId = kp.getId();
    }

    private long startSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions").session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new StartSessionRequest(kpId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpTitle").value("HashMap 的扩容机制是怎样的？"))
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    /** 发起流式请求，等推送结束，返回响应体 */
    private String stream(String path, Object body) throws Exception {
        var request = post(path).session(login).with(csrfToken());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        MvcResult started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                // SSE 规定一律按 UTF-8 解码，响应头里不带 charset；MockMvc 默认按 ISO-8859-1 读，要指定
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 开始学习后流式回复并能取回消息() throws Exception {
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.just("你知道", "负载因子吗？"));
        long sessionId = startSession();

        String body = stream("/api/sessions/" + sessionId + "/reply", null);

        assertThat(body).contains("event:delta", "data:{\"text\":\"你知道\"}", "data:{\"text\":\"负载因子吗？\"}",
                "event:done", "\"ended\":false");
        mockMvc.perform(get("/api/sessions/" + sessionId).session(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].content").value("你知道负载因子吗？"));

        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.just("对。【学习结束】"));
        String answer = stream("/api/sessions/" + sessionId + "/messages", new SendMessageRequest("是 0.75"));
        assertThat(answer).contains("\"marker\":\"END\"", "\"ended\":true").doesNotContain("【学习结束】");
    }

    @Test
    void 不是该回答的时候返回统一格式的409而不是开始推送() throws Exception {
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.just("你知道负载因子吗？"));
        long sessionId = startSession();
        stream("/api/sessions/" + sessionId + "/reply", null);

        mockMvc.perform(post("/api/sessions/" + sessionId + "/reply").session(login).with(csrfToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void 没有登录返回401没有CSRF令牌返回403() throws Exception {
        long sessionId = startSession();

        mockMvc.perform(post("/api/sessions/" + sessionId + "/reply").with(csrfToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sessions/" + sessionId + "/reply").session(login))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isUnauthorized());
    }
}
