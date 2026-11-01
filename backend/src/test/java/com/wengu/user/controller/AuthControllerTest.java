package com.wengu.user.controller;

import com.jayway.jsonpath.JsonPath;
import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.Card;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.user.dto.ChangePasswordRequest;
import com.wengu.user.dto.LoginRequest;
import com.wengu.user.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static com.wengu.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册、登录、鉴权和 CSRF，走完整的过滤器链和真实的 MySQL。
 * 各个测试共用一个库，用户名每次随机生成，互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Test
    void 注册开放时不需要登录就能查到() throws Exception {
        mockMvc.perform(get("/api/auth/registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(true));
    }

    @Test
    void 注册后直接登录() throws Exception {
        String username = newUsername();
        MvcResult result = register(username, PASSWORD, "Asia/Shanghai")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();

        mockMvc.perform(get("/api/auth/me").session(sessionOf(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void 注册时写入默认设置并采用浏览器的时区() throws Exception {
        MockHttpSession session = registered(newUsername(), "America/New_York");

        mockMvc.perform(get("/api/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.data.dailyNewLimit").value(3))
                .andExpect(jsonPath("$.data.dayStartHour").value(4));
    }

    @Test
    void 浏览器时区不合法或没有提交时用默认时区() throws Exception {
        for (String timezone : new String[]{"Mars/Base", "", null}) {
            MockHttpSession session = registered(newUsername(), timezone);
            mockMvc.perform(get("/api/settings").session(session))
                    .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"));
        }
    }

    @Test
    void 用户名已被使用时返回409且不区分大小写() throws Exception {
        String username = newUsername();
        register(username, PASSWORD, null).andExpect(status().isOk());

        register(username.toUpperCase(), PASSWORD, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void 用户名不合规时返回400() throws Exception {
        for (String username : new String[]{"a", "有 空格", "x".repeat(33), "名字!", null}) {
            register(username, PASSWORD, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void 中文用户名可以注册() throws Exception {
        register("温故" + newUsername().substring(0, 6), PASSWORD, null).andExpect(status().isOk());
    }

    @Test
    void 密码不合规时返回400() throws Exception {
        // 7 位太短；65 个字符太长；25 个汉字只有 25 个字符，但有 75 个字节，超过 bcrypt 的 72 字节上限
        for (String password : new String[]{"1234567", "x".repeat(65), "密".repeat(25), null}) {
            register(newUsername(), password, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void 用正确的密码登录() throws Exception {
        String username = newUsername();
        registered(username, null);

        MvcResult result = login(username, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();

        mockMvc.perform(get("/api/auth/me").session(sessionOf(result))).andExpect(status().isOk());
    }

    @Test
    void 密码错误和用户不存在返回同样的401() throws Exception {
        String username = newUsername();
        registered(username, null);

        login(username, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
        login(newUsername(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void 没有密码的默认用户登录不上() throws Exception {
        login("default", "")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    void 退出登录后需要重新登录() throws Exception {
        MockHttpSession session = registered(newUsername(), null);

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrfToken())).andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 登录后更换Session的id() throws Exception {
        String username = newUsername();
        registered(username, null);
        MockHttpSession before = new MockHttpSession();
        String idBefore = before.getId();

        MvcResult result = mockMvc.perform(post("/api/auth/login").session(before).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionOf(result).getId()).isNotEqualTo(idBefore);
    }

    @Test
    void 未登录访问业务接口返回统一格式的401() throws Exception {
        for (String path : new String[]{"/api/settings", "/api/plan/today", "/api/auth/me", "/api/drafts/1"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("请先登录"));
        }
    }

    @Test
    void 没有CSRF令牌的请求返回403() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(newUsername(), PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MockHttpSession session = registered(newUsername(), null);
        mockMvc.perform(patch("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyNewLimit\": 5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 不用测试工具伪造令牌，按浏览器的做法：从 Cookie 中读出令牌，放进请求头。前端就是这样做的。 */
    @Test
    void 从Cookie中读出CSRF令牌放进请求头就能通过() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/auth/registration")).andReturn();
        Cookie token = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        assertThat(token.isHttpOnly()).isFalse();

        mockMvc.perform(post("/api/auth/register").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(newUsername(), PASSWORD, null))))
                .andExpect(status().isOk());
    }

    @Test
    void 修改密码后只能用新密码登录() throws Exception {
        String username = newUsername();
        MockHttpSession session = registered(username, null);

        changePassword(session, PASSWORD, "new-password-2").andExpect(status().isOk());

        login(username, PASSWORD).andExpect(status().isUnauthorized());
        login(username, "new-password-2").andExpect(status().isOk());
        // 当前设备的登录保留
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void 当前密码不对或新密码不合规时不修改() throws Exception {
        String username = newUsername();
        MockHttpSession session = registered(username, null);

        changePassword(session, "wrong-password", "new-password-2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));
        changePassword(session, PASSWORD, "short")
                .andExpect(status().isBadRequest());

        login(username, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void 每个用户只能看到自己的数据() throws Exception {
        String alice = newUsername();
        MvcResult aliceResult = register(alice, PASSWORD, null).andReturn();
        MockHttpSession aliceSession = sessionOf(aliceResult);
        long aliceId = ((Number) JsonPath.read(aliceResult.getResponse().getContentAsString(), "$.data.id"))
                .longValue();
        MockHttpSession bobSession = registered(newUsername(), null);
        newKnowledgePoint(aliceId, "Alice 的知识点");

        mockMvc.perform(patch("/api/settings").session(aliceSession).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyNewLimit\": 7}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/plan/today").session(aliceSession))
                .andExpect(jsonPath("$.data.newItems[0].title").value("Alice 的知识点"));
        mockMvc.perform(get("/api/plan/today").session(bobSession))
                .andExpect(jsonPath("$.data.newItems").isEmpty());
        mockMvc.perform(get("/api/settings").session(bobSession))
                .andExpect(jsonPath("$.data.dailyNewLimit").value(3));
    }

    @Test
    void 日志中不出现密码(CapturedOutput output) throws Exception {
        String username = newUsername();
        register(username, "secret-in-log-1", null).andExpect(status().isOk());
        login(username, "secret-in-log-2").andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains(username)
                .doesNotContain("secret-in-log-1")
                .doesNotContain("secret-in-log-2");
    }

    private ResultActions register(String username, String password, String timezone) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new RegisterRequest(username, password, timezone))));
    }

    /** 注册一个新用户，返回已登录的 Session。 */
    private MockHttpSession registered(String username, String timezone) throws Exception {
        return sessionOf(register(username, PASSWORD, timezone).andExpect(status().isOk()).andReturn());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new LoginRequest(username, password))));
    }

    private ResultActions changePassword(MockHttpSession session, String oldPassword, String newPassword)
            throws Exception {
        return mockMvc.perform(post("/api/auth/password").session(session).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new ChangePasswordRequest(oldPassword, newPassword))));
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void newKnowledgePoint(long userId, String title) {
        Instant now = Instant.now();
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(KnowledgePointType.CONCEPT);
        kp.setTitle(title);
        kp.setSummary("要点");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(now);
        kp.setUpdatedAt(now);
        knowledgePointMapper.insert(kp);
        cardStateMapper.insert(CardState.of(kp.getId(), userId, Card.newCard(now)));
    }
}
