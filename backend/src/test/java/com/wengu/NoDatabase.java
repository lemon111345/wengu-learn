package com.wengu;

/**
 * 只调用大模型、用不到数据库的启动方式（LLM 实连测试、命令行实验入口）用它排除数据源和 Flyway 的自动配置，
 * 不需要启动 MySQL。
 * <p>
 * 没有数据源时 MyBatis 不会注册 Mapper，依赖 Mapper 的 Service 无法创建，所以同时开启懒加载：
 * 只创建实际用到的 bean，用不到数据库的测试就不会碰到这些 Service。启动事件的监听者不受懒加载影响，
 * 要访问数据库的那个（抽取的重启处理）用 {@link #NO_RECOVERY_PROPERTY} 关掉。
 */
public final class NoDatabase {

    public static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    public static final String EXCLUDE_VALUE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    public static final String LAZY_KEY = "spring.main.lazy-initialization";

    /** 关掉启动时的抽取重启处理（ExtractionRecovery）：启动事件会强制创建它，没有数据库时创建不出来。 */
    public static final String NO_RECOVERY_KEY = "wengu.extraction.recover-on-startup";

    /** 用于 @SpringBootTest(properties = ...)。 */
    public static final String PROPERTY = EXCLUDE_KEY + "=" + EXCLUDE_VALUE;

    /** 用于 @SpringBootTest(properties = ...)，和 {@link #PROPERTY} 一起使用。 */
    public static final String LAZY_PROPERTY = LAZY_KEY + "=true";

    /** 用于 @SpringBootTest(properties = ...)，和 {@link #PROPERTY} 一起使用。 */
    public static final String NO_RECOVERY_PROPERTY = NO_RECOVERY_KEY + "=false";

    private NoDatabase() {
    }
}
