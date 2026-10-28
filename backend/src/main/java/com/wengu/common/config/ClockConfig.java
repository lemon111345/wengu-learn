package com.wengu.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 需要“现在”的代码都注入 Clock，用 Instant.now(clock) 取时间，不直接调用 Instant.now()。
 * 测试时换成 Clock.fixed(...)，就能测“今天”的边界（如凌晨 3:59 和 4:00）。
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
