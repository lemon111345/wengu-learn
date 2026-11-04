package com.wengu.source.controller;

import com.wengu.TestcontainersConfiguration;
import com.wengu.llm.dto.LlmResult;
import com.wengu.source.dto.GenerateResult;
import com.wengu.source.dto.GeneratedQuestion;
import com.wengu.source.service.QuestionGenerateService;
import com.wengu.source.service.QuestionSplitService;
import com.wengu.user.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.wengu.TestCsrf.csrfToken;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传文件导入的 HTTP 接口（#26）：完整的过滤器链（登录、CSRF）和 multipart 解析。模型用假的。
 * 文件超过 10 MB 的情况要真实的 Tomcat 解析 multipart 才会出现，MockMvc 测不到，在端到端验证中检查。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SourceControllerTest {

    private static final String NOTE = "# 线程池笔记\n\n## 核心参数\n\ncorePoolSize 是核心线程数。\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private QuestionGenerateService generateService;

    @MockitoBean
    private QuestionSplitService splitService;

    private MockHttpSession login;

    @BeforeEach
    void register() throws Exception {
        String username = "src" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(username, "correct-horse-1", null))))
                .andExpect(status().isOk())
                .andReturn();
        login = (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 上传讲解资料_提取文字后整理题目_没填名称时用文件名() throws Exception {
        when(generateService.generate(anyString())).thenReturn(new LlmResult<>(new GenerateResult(List.of(
                new GeneratedQuestion("核心参数", "线程池有哪些核心参数？", List.of()))),
                "{...}", "test-model", "stop", 100, 50));

        mockMvc.perform(multipart("/api/sources/upload").file(file("线程池笔记.md", NOTE))
                        .param("type", "DOCUMENT").session(login).with(csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("线程池笔记"))
                .andExpect(jsonPath("$.data.type").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.questions[0].text").value("线程池有哪些核心参数？"));
        // 发给模型的是提取并整理过的文字
        verify(generateService).generate(eq(NOTE.strip()));
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 不支持的格式_没有选文件_没有登录() throws Exception {
        mockMvc.perform(multipart("/api/sources/upload").file(file("旧笔记.doc", "x"))
                        .session(login).with(csrfToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("暂不支持旧版 Word 文件（.doc），请在 Word 中另存为 .docx 再上传"));

        mockMvc.perform(multipart("/api/sources/upload").param("type", "DOCUMENT")
                        .session(login).with(csrfToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少参数: file"));

        mockMvc.perform(multipart("/api/sources/upload").file(file("笔记.md", NOTE)).with(csrfToken()))
                .andExpect(status().isUnauthorized());
    }
}
