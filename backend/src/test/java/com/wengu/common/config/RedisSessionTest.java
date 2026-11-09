package com.wengu.common.config;

import com.wengu.TestcontainersConfiguration;
import com.wengu.user.dto.LoginRequest;
import com.wengu.user.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.wengu.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录状态存进 Redis（决策 040）：走完整的过滤器链，用 Testcontainers 启动真实的 Redis，不依赖外部服务。
 * 其他测试在 src/test/resources/application.properties 中关掉了 Spring Session，这里清空那一项。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedisSessionTest {

    private static final String PASSWORD = "correct-horse-1";
    private static final String KEY_PREFIX = "spring:session:sessions:";

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StringRedisTemplate redis;

    /** 注册一个新用户（注册后直接登录），返回响应 */
    private MvcResult register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(username, PASSWORD, "Asia/Shanghai"))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).as("响应中应该有 SESSION Cookie").isNotNull();
        return cookie;
    }

    /** Cookie 中的值是 Session id 的 Base64 */
    private static String redisKey(Cookie cookie) {
        return KEY_PREFIX + new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    @Test
    void 登录状态存在Redis中_只带Cookie就能继续访问() throws Exception {
        String username = newUsername();
        MvcResult registered = register(username);
        Cookie cookie = sessionCookie(registered);

        assertThat(redis.hasKey(redisKey(cookie))).isTrue();
        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void 空闲30天失效() throws Exception {
        Cookie cookie = sessionCookie(register(newUsername()));

        Long ttl = redis.getExpire(redisKey(cookie), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofDays(29).toSeconds(), Duration.ofDays(30).toSeconds());
    }

    @Test
    void 登录时更换Session的id_旧的立即失效() throws Exception {
        String username = newUsername();
        Cookie before = sessionCookie(register(username));

        MvcResult login = mockMvc.perform(post("/api/auth/login").cookie(before).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie after = sessionCookie(login);

        assertThat(after.getValue()).isNotEqualTo(before.getValue());
        assertThat(redis.hasKey(redisKey(before))).isFalse();
        assertThat(redis.hasKey(redisKey(after))).isTrue();
        mockMvc.perform(get("/api/auth/me").cookie(before)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(after)).andExpect(status().isOk());
    }

    @Test
    void 退出登录后Redis中的Session被删除() throws Exception {
        Cookie cookie = sessionCookie(register(newUsername()));

        mockMvc.perform(post("/api/auth/logout").cookie(cookie).with(csrfToken())).andExpect(status().isOk());

        assertThat(redis.hasKey(redisKey(cookie))).isFalse();
        mockMvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void 未登录的请求不创建Session() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/registration")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getCookie("SESSION")).isNull();
    }

    @Test
    void 读不出来的Session按未登录处理_不报500() throws Exception {
        Cookie cookie = sessionCookie(register(newUsername()));
        // 模拟升级依赖后序列化格式变了：登录信息这一项读不出来
        redis.opsForHash().put(redisKey(cookie), "sessionAttr:SPRING_SECURITY_CONTEXT", "不是合法的序列化数据");

        mockMvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
