package com.wengu.common;

import com.wengu.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 迁移脚本在空库上执行的结果。启动时 Flyway 已经执行过一次 V1。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void 空库启动后建出全部表() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);

        assertThat(tables).contains("user", "user_settings", "source", "knowledge_point", "kp_source",
                "card_state", "study_session", "message", "review_log");
    }

    @Test
    void 插入默认用户和默认设置() {
        Map<String, Object> settings = jdbc.queryForMap(
                "SELECT u.username, s.daily_new_limit, s.timezone, s.day_start_hour"
                        + " FROM `user` u JOIN user_settings s ON s.user_id = u.id WHERE u.id = 1");

        assertThat(settings).containsEntry("username", "default")
                .containsEntry("daily_new_limit", 3)
                .containsEntry("timezone", "Asia/Shanghai");
        assertThat(((Number) settings.get("day_start_hour")).intValue()).isEqualTo(4);
    }

    @Test
    void 再次执行迁移不会重复执行已有脚本() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    @Transactional
    void Instant按UTC写入DATETIME() {
        jdbc.update("INSERT INTO study_session (user_id, kp_id, started_at) VALUES (1, 1, ?)",
                Instant.parse("2026-01-01T00:00:00Z"));

        String stored = jdbc.queryForObject(
                "SELECT DATE_FORMAT(started_at, '%Y-%m-%d %H:%i:%s') FROM study_session WHERE kp_id = 1", String.class);

        // 如果按 JVM 的本地时区（如上海）写入，这里会是 08:00:00
        assertThat(stored).isEqualTo("2026-01-01 00:00:00");
    }
}
