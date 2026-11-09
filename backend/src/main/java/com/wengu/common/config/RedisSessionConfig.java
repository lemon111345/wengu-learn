package com.wengu.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * 登录状态存进 Redis 时的序列化（决策 040）。
 * <p>
 * 仍然用 JDK 序列化，只加一层容错：升级依赖后，Redis 里的旧 Session 可能读不出来，
 * 默认会抛异常，带着旧 Cookie 的每个请求都是 500，用户自己解决不了。读不出来的属性按不存在处理，也就是未登录，重新登录即可。
 */
@Configuration(proxyBeanMethods = false)
class RedisSessionConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionConfig.class);

    /** 方法名就是 bean 名，Spring Session 按这个名字找序列化器，不能改。 */
    @Bean
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        RedisSerializer<Object> jdk = RedisSerializer.java(RedisSessionConfig.class.getClassLoader());
        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object value) {
                return jdk.serialize(value);
            }

            @Override
            public Object deserialize(byte[] bytes) {
                try {
                    return jdk.deserialize(bytes);
                } catch (SerializationException e) {
                    log.warn("Session 中有读不出来的属性，按未登录处理：{}", e.getMessage());
                    return null;
                }
            }
        };
    }
}
