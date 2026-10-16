package com.example.rbac.client.it;

import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.cache.RbacL2Redis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the starter can **isolate its L2 Redis from the host application's Redis**
 * (REVIEW #9). This models the *dangerous* case: the host **has** its own Redis
 * (so the classic bug is reading L2 from the host's session/data Redis). With
 * {@code rbac.client.redis.*} configured, the starter must take its **dedicated**
 * path and back the {@link L2Cache} with its **own** connection — NOT the host's
 * {@code StringRedisTemplate}.
 *
 * <p>No Docker / live Redis is needed: Lettuce connects lazily, so booting connection
 * factories against unreachable hosts succeeds (no network I/O at init). Rabbit
 * auto-config is excluded so the invalidation subscriber does not try to reach a broker.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = RbacClientRedisIsolationTest.TestApp.class,
        properties = {
                "rbac.client.redis.host=127.0.0.1",
                "rbac.client.redis.port=6399",
                "rbac.client.redis.password=it-secret",
                "rbac.client.redis.database=3"
        })
class RbacClientRedisIsolationTest {

    /**
     * A host that HAS its own Redis (the default {@code RedisAutoConfiguration} is active,
     * creating a {@code stringRedisTemplate} bean) but points it at the *wrong* place for
     * our purposes. Rabbit auto-config is excluded only to avoid needing a broker.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {RabbitAutoConfiguration.class})
    static class TestApp {
    }

    @Autowired ConfigurableApplicationContext ctx;

    @Test
    void dedicatedL2RedisIsUsed_whenHostHasItsOwnRedis() {
        StringRedisTemplate hostTemplate = ctx.getBean("stringRedisTemplate", StringRedisTemplate.class);
        assertThat(hostTemplate).as("the host has its own StringRedisTemplate (the dangerous case)").isNotNull();

        // The starter must take the DEDICATED path and NOT the host-fallback path.
        assertThat(ctx.containsBean("rbacL2CacheDedicated")).as("dedicated L2 path is active").isTrue();
        assertThat(ctx.containsBean("rbacL2Cache")).as("host-fallback L2 path is NOT active").isFalse();
        assertThat(ctx.getBean("rbacL2Redis", RbacL2Redis.class))
                .as("the starter's dedicated, self-managed L2 connection").isNotNull();

        // And the L2Cache's template must be the starter's own — not the host's template bean.
        L2Cache l2 = ctx.getBean(L2Cache.class);
        Object l2Template = field(l2, "redis");
        assertThat(l2Template).as("the L2Cache's Redis template")
                .isNotNull()
                .isNotSameAs(hostTemplate);
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }
}
