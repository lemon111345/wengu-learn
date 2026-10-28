package com.wengu;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 需要数据库的 @SpringBootTest 用 @Import 引入这个配置：用 Docker 启动一个 MySQL 8.4，
 * 由 @ServiceConnection 把连接信息交给 Spring，不需要配置 url。运行前要先打开 Docker。
 * 同一份配置的测试共用 Spring 的上下文缓存，容器只启动一次。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        // 和 application.yml 一样按 UTC 连接，@ServiceConnection 会用容器给出的这个 url
        return new MySQLContainer("mysql:8.4")
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true");
    }
}
