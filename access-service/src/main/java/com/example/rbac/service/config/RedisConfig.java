package com.example.rbac.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis is used for (a) the L2 shared grant cache and (b) the policy version.
 * Both are plain strings: the L2 value is JSON TEXT owned by {@code GrantCache}
 * (it serializes with its own ObjectMapper), and the version is a raw number
 * written by INCR. A pure-String template keeps the L2 wire format byte-identical
 * to what the starter's L2Cache reads (Spring Boot's default StringRedisTemplate),
 * so the PDP (writer) and the clients (readers) agree on the value format.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
