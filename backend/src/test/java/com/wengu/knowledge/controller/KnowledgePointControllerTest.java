package com.wengu.knowledge.controller;

import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.user.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static com.wengu.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库和知识点操作的 HTTP 接口（#50、#25）：完整的过滤器链（登录、CSRF）、错误时返回统一格式。
 * 业务规则的各种情况在 KnowledgeLibraryServiceTest、KnowledgePointServiceTest 中测；各个测试的用户名随机生成，互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KnowledgePointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KnowledgePointService knowledgePointService;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    private MockHttpSession login;
    private long kpId;

    @BeforeEach
    void registerAndAddKnowledgePoint() throws Exception {
        String username = "kp" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(username, "correct-horse-1", null))))
                .andExpect(status().isOk())
                .andReturn();
        login = (MockHttpSession) result.getRequest().getSession(false);
        long userId = jsonMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
        kpId = knowledgePointService.create(userId, null, List.of(new KnowledgePointService.NewKnowledgePoint(
                KnowledgePointType.CONCEPT, "什么是 volatile？", "要点", null))).getFirst();
    }

    @Test
    void 知识库列表() throws Exception {
        mockMvc.perform(get("/api/knowledge-points").session(login)
                        .param("type", "CONCEPT").param("sort", "MASTERY").param("direction", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.counts.NEW").value(1))
                .andExpect(jsonPath("$.data.counts.SOLID").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(kpId))
                .andExpect(jsonPath("$.data.items[0].title").value("什么是 volatile？"))
                .andExpect(jsonPath("$.data.items[0].type").value("CONCEPT"))
                .andExpect(jsonPath("$.data.items[0].mastery").value("NEW"))
                .andExpect(jsonPath("$.data.items[0].nextReview").isEmpty());
    }

    @Test
    void 知识点详情() throws Exception {
        mockMvc.perform(get("/api/knowledge-points/" + kpId).session(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(kpId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.mastery").value("NEW"))
                .andExpect(jsonPath("$.data.summary").value("要点"))
                .andExpect(jsonPath("$.data.summaryEdited").value(false))
                .andExpect(jsonPath("$.data.reviews").isArray())
                .andExpect(jsonPath("$.data.sources").isArray());

        mockMvc.perform(get("/api/knowledge-points/" + Long.MAX_VALUE).session(login))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 列表参数不合法时返回400() throws Exception {
        for (String[] param : List.of(new String[]{"mastery", "GOOD"}, new String[]{"type", "QUIZ"},
                new String[]{"sort", "TITLE"}, new String[]{"direction", "UP"})) {
            mockMvc.perform(get("/api/knowledge-points").session(login).param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void 没有登录时不能看列表() throws Exception {
        mockMvc.perform(get("/api/knowledge-points"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 编辑知识点() throws Exception {
        mockMvc.perform(patch("/api/knowledge-points/" + kpId).session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"volatile 保证了什么？\", \"type\": \"MECHANISM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        assertThat(knowledgePointMapper.selectById(kpId).getTitle()).isEqualTo("volatile 保证了什么？");
        assertThat(knowledgePointMapper.selectById(kpId).getType()).isEqualTo(KnowledgePointType.MECHANISM);
    }

    @Test
    void 标题为空时返回400() throws Exception {
        mockMvc.perform(patch("/api/knowledge-points/" + kpId).session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("标题不能为空"));
    }

    @Test
    void 不存在的类型返回400() throws Exception {
        mockMvc.perform(patch("/api/knowledge-points/" + kpId).session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"QUIZ\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 别人的知识点返回404() throws Exception {
        String other = "kp" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(other, "correct-horse-1", null))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession otherLogin = (MockHttpSession) result.getRequest().getSession(false);

        mockMvc.perform(patch("/api/knowledge-points/" + kpId).session(otherLogin).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"改别人的\"}"))
                .andExpect(status().isNotFound());
        assertThat(knowledgePointMapper.selectById(kpId).getTitle()).isEqualTo("什么是 volatile？");
    }

    @Test
    void 没有CSRF令牌时拒绝() throws Exception {
        mockMvc.perform(patch("/api/knowledge-points/" + kpId).session(login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"新标题\"}"))
                .andExpect(status().isForbidden());
    }

    private void postIds(String action, long id, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/knowledge-points/" + action).session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [" + id + "]}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void 暂停_删除_撤销() throws Exception {
        postIds("suspend", kpId, 200);
        assertThat(knowledgePointMapper.selectById(kpId).getStatus()).isEqualTo(KnowledgePointStatus.SUSPENDED);

        postIds("delete", kpId, 200);
        assertThat(knowledgePointMapper.selectById(kpId).getDeletedAt()).isNotNull();
        // 删除后不能再暂停、恢复、编辑
        postIds("resume", kpId, 404);

        postIds("restore", kpId, 200);
        assertThat(knowledgePointMapper.selectById(kpId).getDeletedAt()).isNull();
        postIds("resume", kpId, 200);
        assertThat(knowledgePointMapper.selectById(kpId).getStatus()).isEqualTo(KnowledgePointStatus.ACTIVE);
    }

    @Test
    void 没有选择知识点时返回400() throws Exception {
        mockMvc.perform(post("/api/knowledge-points/delete").session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("没有选择知识点"));
    }

    @Test
    void 合并() throws Exception {
        long userId = knowledgePointMapper.selectById(kpId).getUserId();
        long other = knowledgePointService.create(userId, null, List.of(new KnowledgePointService.NewKnowledgePoint(
                KnowledgePointType.CONCEPT, "volatile 有什么用？", "要点", null))).getFirst();

        mockMvc.perform(post("/api/knowledge-points/merge").session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryId\": " + kpId + ", \"mergedIds\": [" + other + "]}"))
                .andExpect(status().isOk());

        assertThat(knowledgePointMapper.selectById(other).getMergedInto()).isEqualTo(kpId);
    }
}
