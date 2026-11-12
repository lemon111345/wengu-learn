package com.wengu;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 只调用大模型、用不到数据库的启动方式（LLM 实连测试、命令行实验入口）用它排除数据源和 Flyway 的自动配置，
 * 不需要启动 MySQL。
 * <p>
 * 没有数据源时 MyBatis 不会注册 Mapper，依赖 Mapper 的 Service 无法创建，所以同时开启懒加载：
 * 只创建实际用到的 bean，用不到数据库的测试就不会碰到这些 Service。启动事件的监听者不受懒加载影响，
 * 要访问数据库的那个（抽取的重启处理）用 {@link #NO_RECOVERY_PROPERTY} 关掉。
 * <p>
 * 测试目录的 application.properties 也设置了 spring.autoconfigure.exclude（排除 Spring Session），同一个键只认优先级
 * 最高的那一处，所以这里的排除项要把 Session 的两个也带上。
 */
public final class NoDatabase {

    public static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    public static final String EXCLUDE_VALUE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
            + "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
            + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration";

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

    /**
     * 命令行实验入口用：不启动 Web 服务，不连数据库。
     * <p>
     * 这三项用命令行参数传入。原来放在 setDefaultProperties 中，优先级最低，被测试目录 application.properties 的
     * spring.autoconfigure.exclude 覆盖，结果入口仍然去连 MySQL（#89）。命令行参数的优先级高于配置文件；
     * 放在 args 前面，调用者在命令行上传的同名参数仍然可以改掉它们。
     */
    public static ConfigurableApplicationContext start(String[] args) {
        SpringApplication app = new SpringApplication(WenguApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN"));
        String[] all = Stream.concat(Stream.of("--" + PROPERTY, "--" + LAZY_PROPERTY, "--" + NO_RECOVERY_PROPERTY),
                Arrays.stream(args)).toArray(String[]::new);
        return app.run(all);
    }
}
