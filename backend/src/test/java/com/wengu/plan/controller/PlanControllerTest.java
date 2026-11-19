package com.wengu.plan.controller;

import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.plan.dto.PickRequest;
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
import java.util.stream.IntStream;

import static com.wengu.TestCsrf.csrfToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 加入今天的接口（#128，决策 052）。计算规则在 PlanServiceTest 里测，这里只测接口的形状。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KnowledgePointService knowledgePointService;

    private MockHttpSession login;
    private List<Long> kpIds;

    @BeforeEach
    void registerAndAddKnowledgePoints() throws Exception {
        String username = "plan" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new RegisterRequest(username, "correct-horse-1", null))))
                .andExpect(status().isOk())
                .andReturn();
        login = (MockHttpSession) result.getRequest().getSession(false);
        long userId = jsonMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
        // 新用户默认每天学 5 个，第 6 个不会被派发
        kpIds = knowledgePointService.create(userId, null, IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new KnowledgePointService.NewKnowledgePoint(KnowledgePointType.CONCEPT, "问题 " + i, "要点", null))
                .toList());
    }

    @Test
    void 加入今天() throws Exception {
        mockMvc.perform(post("/api/plan/today/picks").session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new PickRequest(List.of(kpIds.get(0), kpIds.get(5), Long.MAX_VALUE)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(1))
                .andExpect(jsonPath("$.data.alreadyInToday").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void 没有选择时返回400() throws Exception {
        mockMvc.perform(post("/api/plan/today/picks").session(login).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new PickRequest(List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
