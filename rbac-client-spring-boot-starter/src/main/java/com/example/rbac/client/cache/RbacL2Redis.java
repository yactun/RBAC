package com.example.rbac.client.cache;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A private, self-managed Redis connection for the starter's L2 cache (REVIEW #9).
 *
 * <p>Why a dedicated wrapper instead of a bare {@code LettuceConnectionFactory} bean: a
 * {@code LettuceConnectionFactory} also implements {@code RedisConnectionFactory} and
 * {@code ReactiveRedisConnectionFactory}. Registering one as a bean would collide with a
 * host application's own Redis auto-configuration (e.g. its {@code reactiveRedisTemplate}
 * expects a <i>single</i> {@code ReactiveRedisConnectionFactory}), and would let the starter
 * accidentally read the host's session/data Redis. This wrapper is a distinct type that
 * implements none of those interfaces, so it cannot be picked up by type-based autowiring —
 * the L2 uses exactly this connection and nothing else.
 *
 * <p>Lifecycle: the factory is started on construction and destroyed by Spring
 * ({@link DisposableBean}) when the context closes, so the connection pool is released.
 */
public class RbacL2Redis implements DisposableBean {

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    public RbacL2Redis(String host, int port, String password, int database) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(RedisPassword.of(password));
        }
        if (database > 0) {
            cfg.setDatabase(database);
        }
        this.factory = new LettuceConnectionFactory(cfg);
        this.factory.setValidateConnection(true);
        this.factory.afterPropertiesSet();
        // A StringRedisTemplate bound to OUR connection only (never the host's).
        this.template = new StringRedisTemplate(this.factory);
    }

    /** The dedicated {@code StringRedisTemplate} the L2 cache reads through. */
    public StringRedisTemplate template() {
        return template;
    }

    @Override
    public void destroy() {
        factory.destroy();
    }
}
