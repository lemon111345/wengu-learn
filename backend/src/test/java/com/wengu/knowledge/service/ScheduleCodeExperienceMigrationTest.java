package com.wengu.knowledge.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移 V18（决策 051）：给已经确认的带源码个人经历题补上调度状态。Flyway 在空库上已经跑过一次，
 * 这里先造数据，再把同一份 SQL 执行一遍，看补得对不对。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ScheduleCodeExperienceMigrationTest {

    private static final String MIGRATION = "db/migration/V18__schedule_code_experience.sql";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CardStateMapper cardStateMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM card_state");
        jdbc.update("DELETE FROM kp_code");
        jdbc.update("DELETE FROM knowledge_point");
    }

    private long kp(String type) {
        jdbc.update("INSERT INTO knowledge_point (user_id, type, title, summary, status, sort_order, created_at, updated_at)"
                + " VALUES (1, ?, '标题', '要点', 'ACTIVE', 1, NOW(3), NOW(3))", type);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void code(long kpId) {
        jdbc.update("INSERT INTO kp_code (kp_id, user_id, source_id, evidence, unverifiable, prerequisites)"
                + " VALUES (?, 1, 1, '[]', '[]', '[]')", kpId);
    }

    @Test
    void 只给没有调度状态的带源码个人经历题补新卡() throws Exception {
        long withCode = kp("EXPERIENCE");
        code(withCode);
        long plain = kp("EXPERIENCE");
        long alreadyHasCard = kp("EXPERIENCE");
        code(alreadyHasCard);
        jdbc.update("INSERT INTO card_state (kp_id, user_id, state, step, due) VALUES (?, 1, 'REVIEW', NULL, NOW(3))",
                alreadyHasCard);

        jdbc.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));

        CardState added = cardStateMapper.selectById(withCode);
        assertThat(added.getUserId()).isEqualTo(1L);
        assertThat(added.getState()).isEqualTo(State.LEARNING);
        assertThat(added.getStep()).isZero();
        assertThat(added.getStability()).isNull();
        assertThat(added.getLastReview()).isNull();
        assertThat(cardStateMapper.selectById(plain)).isNull();
        // 已有的不动
        assertThat(cardStateMapper.selectById(alreadyHasCard).getState()).isEqualTo(State.REVIEW);
    }
}
