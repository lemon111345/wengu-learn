package com.wengu.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示测试中如何把 Clock 换成固定时间：再声明一个标了 @Primary 的 Clock，注入时优先用它。
 * 只加载这两个配置类，不启动整个应用。
 */
@SpringJUnitConfig({ClockConfig.class, ClockConfigTest.FixedClock.class})
class ClockConfigTest {

    /** 上海时间 2026-01-02 03:59，按默认的 4 点分界还属于 1 月 1 日。 */
    static final Instant FIXED = Instant.parse("2026-01-01T19:59:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private Clock clock;

    @Test
    void 注入的是固定时间() {
        assertThat(Instant.now(clock)).isEqualTo(FIXED);
    }

    @Test
    void 默认的Clock是UTC() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
