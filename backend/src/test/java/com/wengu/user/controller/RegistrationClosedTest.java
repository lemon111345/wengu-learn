package com.wengu.user.controller;

import com.wengu.TestcontainersConfiguration;
import com.wengu.user.dto.LoginRequest;
import com.wengu.user.dto.RegisterRequest;
import com.wengu.user.entity.User;
import com.wengu.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static com.wengu.TestCsrf.csrfToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 部署时用 WENGU_ALLOW_REGISTRATION 关掉了注册（决策 017）。配置不同，要单独启动一个 Spring 上下文。 */
@SpringBootTest(properties = "wengu.auth.allow-registration=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationClosedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void 注册页显示暂未开放注册() throws Exception {
        mockMvc.perform(get("/api/auth/registration"))
                .andExpect(jsonPath("$.data.open").value(false));
    }

    @Test
    void 注册返回403() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                new RegisterRequest("newcomer", "correct-horse-1", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"))
                .andExpect(jsonPath("$.message").value("暂未开放注册"));
    }

    @Test
    void 已有用户照常登录() throws Exception {
        User user = new User();
        user.setUsername("existing");
        user.setPasswordHash(passwordEncoder.encode("correct-horse-1"));
        user.setCreatedAt(Instant.now());
        userMapper.insert(user);

        mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest("existing", "correct-horse-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("existing"));
    }
}
