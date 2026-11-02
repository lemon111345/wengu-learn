package com.wengu;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 需要数据库的 @SpringBootTest 用 @Import 引入这个配置：用 Docker 启动一个 MySQL 8.4，
 * 由 @ServiceConnection 把连接信息交给 Spring，不需要配置 url。运行前要先打开 Docker。
 * <p>
 * <b>所有测试共用同一个容器。</b>每种 @MockitoBean / @TestBean 的组合都是一个单独的 Spring 上下文，
 * 缓存的上下文不会关闭；容器如果跟着上下文走，测试类一多就同时开着十几个 MySQL，Docker 内存不够时新容器启动失败。
 * 共用之后，各个测试自己清理用到的表，不能假设数据库是空的；要空库的测试自己建一个库（见 DefaultUserMigrationTest）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // 和 application.yml 一样按 UTC 连接，@ServiceConnection 会用容器给出的这个 url
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    /**
     * destroyMethod 置空：某个上下文关闭时不停容器，其他上下文还在用。JVM 退出后由 Testcontainers 清理容器。
     * 第一个上下文启动时由 Spring Boot 启动容器，之后再启动是空操作。
     */
    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysql() {
        return MYSQL;
    }
}
