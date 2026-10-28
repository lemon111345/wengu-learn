package com.wengu;

/**
 * 只调用大模型、用不到数据库的启动方式（LLM 实连测试、命令行实验入口）用它排除数据源和 Flyway 的自动配置，
 * 不需要启动 MySQL。
 */
public final class NoDatabase {

    public static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    public static final String EXCLUDE_VALUE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    /** 用于 @SpringBootTest(properties = ...)。 */
    public static final String PROPERTY = EXCLUDE_KEY + "=" + EXCLUDE_VALUE;

    private NoDatabase() {
    }
}
