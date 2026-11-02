package com.wengu.common;

import com.wengu.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 删除空的默认用户，以及 tools/migrate-default-user.sql 把默认用户的数据转给新账号（决策 017 第 8 条）。
 * <p>
 * 要先迁移到 V6、插入数据，再执行 V7，所以每个测试单独建一个空库，不用 Spring 启动时已经迁移完的那个。
 * 建库要 root 权限，Testcontainers 的 root 密码和普通账号相同。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DefaultUserMigrationTest {

    /** V7 和一次性脚本中列出的业务表。 */
    private static final List<String> BUSINESS_TABLES = List.of("source", "knowledge_point", "kp_source", "card_state",
            "study_session", "message", "review_log", "extraction_draft", "extraction_batch", "extraction_draft_item");

    @Autowired
    private MySQLContainer mysql;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createEmptyDatabase() {
        String database = "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()))
                .execute("CREATE DATABASE " + database);
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName() + "?", "/" + database + "?");
        dataSource = new DriverManagerDataSource(url, "root", mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    /** V1 插入的默认用户在空库上没有业务数据，迁移到最新后它和它的设置都被删掉（决策 017）。 */
    @Test
    void 空库迁移完没有默认用户() {
        migrate(null);

        assertThat(count("SELECT COUNT(*) FROM `user` WHERE username = 'default'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = 1")).isZero();
    }

    @Test
    void 默认用户没有业务数据时被删除() {
        migrate("6");
        // 其他用户的数据不影响
        long other = insertUser("someone");
        insertBusinessData(other);

        migrate(null);

        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = ?", other)).isEqualTo(1);
    }

    @Test
    void 默认用户有业务数据时不动() {
        migrate("6");
        insertBusinessData(1);

        migrate(null);

        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = 1")).isEqualTo(1);
    }

    @Test
    void 只有一张表有数据也算有业务数据() {
        migrate("6");
        jdbc.update("INSERT INTO message (user_id, session_id, role, content, created_at)"
                + " VALUES (1, 1, 'USER', '你好', UTC_TIMESTAMP(3))");

        migrate(null);

        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = 1")).isEqualTo(1);
    }

    @Test
    void 一次性脚本把默认用户的数据转给新账号() throws Exception {
        migrate("6");
        insertBusinessData(1);
        migrate(null);
        long author = insertUser("author");
        insertSettings(author);

        runToolScript("author");

        for (String table : BUSINESS_TABLES) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE user_id = 1")).as(table).isZero();
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", author)).as(table).isEqualTo(1);
        }
        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_settings WHERE user_id = ?", author)).isEqualTo(1);
    }

    @Test
    void 一次性脚本中的用户名写错时什么都不改() throws Exception {
        migrate("6");
        insertBusinessData(1);
        migrate(null);
        insertUser("author");

        runToolScript("nobody");

        for (String table : BUSINESS_TABLES) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE user_id = 1")).as(table).isEqualTo(1);
        }
        assertThat(count("SELECT COUNT(*) FROM `user` WHERE id = 1")).isEqualTo(1);
    }

    /** target 为空时迁移到最新。 */
    private void migrate(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("default_timezone", "Asia/Shanghai"));
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    /** 每张业务表插入一行，都属于 userId。各行之间的 id 不必对应，这里只看 user_id。 */
    private void insertBusinessData(long userId) {
        jdbc.update("INSERT INTO source (user_id, type, name, content, created_at)"
                + " VALUES (?, 'QUESTION', '题目', '什么是 MVCC？', UTC_TIMESTAMP(3))", userId);
        jdbc.update("INSERT INTO knowledge_point (user_id, type, title, summary, status, sort_order, created_at,"
                + " updated_at) VALUES (?, 'CONCEPT', '什么是 MVCC？', '要点', 'ACTIVE', 1, UTC_TIMESTAMP(3),"
                + " UTC_TIMESTAMP(3))", userId);
        long kpId = count("SELECT MAX(id) FROM knowledge_point");
        jdbc.update("INSERT INTO kp_source (kp_id, source_id, user_id) VALUES (?, 1, ?)", kpId, userId);
        jdbc.update("INSERT INTO card_state (kp_id, user_id, state, due) VALUES (?, ?, 'LEARNING', UTC_TIMESTAMP(3))",
                kpId, userId);
        jdbc.update("INSERT INTO study_session (user_id, kp_id, started_at) VALUES (?, ?, UTC_TIMESTAMP(3))",
                userId, kpId);
        jdbc.update("INSERT INTO message (user_id, session_id, role, content, created_at)"
                + " VALUES (?, 1, 'USER', '你好', UTC_TIMESTAMP(3))", userId);
        jdbc.update("INSERT INTO review_log (user_id, kp_id, rating_user, state_before, is_first_review, reviewed_at)"
                + " VALUES (?, ?, 'GOOD', 'LEARNING', TRUE, UTC_TIMESTAMP(3))", userId, kpId);
        jdbc.update("INSERT INTO extraction_draft (user_id, source_id, created_at) VALUES (?, 1, UTC_TIMESTAMP(3))",
                userId);
        jdbc.update("INSERT INTO extraction_batch (user_id, draft_id, batch_no, status, questions, attempts, created_at)"
                + " VALUES (?, 1, 1, 'READY', '[]', 1, UTC_TIMESTAMP(3))", userId);
        jdbc.update("INSERT INTO extraction_draft_item (user_id, draft_id, batch_id, item_order, title, summary, type,"
                + " source_question_ids) VALUES (?, 1, 1, 1, '什么是 MVCC？', '要点', 'CONCEPT', '1')", userId);
    }

    private long insertUser(String username) {
        jdbc.update("INSERT INTO `user` (username, password_hash, created_at) VALUES (?, '{noop}x', UTC_TIMESTAMP(3))",
                username);
        return count("SELECT id FROM `user` WHERE username = ?", username);
    }

    private void insertSettings(long userId) {
        jdbc.update("INSERT INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (?, 3, 'Asia/Shanghai', 4)", userId);
    }

    /** 执行 tools/ 下的一次性脚本，用户名按使用说明替换成 username。Maven 运行测试时的当前目录是 backend/。 */
    private void runToolScript(String username) throws Exception {
        String script = Files.readString(Path.of("..", "tools", "migrate-default-user.sql"), StandardCharsets.UTF_8)
                .replace("'改成你的用户名'", "'" + username + "'");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
