package com.example.rbac.client.autoconfigure;

import com.example.rbac.client.AccessClient;
import com.example.rbac.client.DefaultAccessClient;
import com.example.rbac.client.RbacClientMetrics;
import com.example.rbac.client.RbacClientProperties;
import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.cache.RbacL2Redis;
import com.example.rbac.client.invalidation.InvalidationHandler;
import com.example.rbac.client.invalidation.InvalidationSubscriber;
import com.example.rbac.client.security.UserContextResolver;
import com.example.rbac.model.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Set;

/**
 * Auto-configuration for the shared RBAC client. Wires the {@link AccessClient}
 * and, when the corresponding infrastructure is on the classpath, the L2 (Redis)
 * cache and the RabbitMQ invalidation subscriber. Each of those is optional, so a
 * host app can run the client with L1-only if it wishes (ADR D5).
 *
 * <p>The host app is responsible for resolving the {@link UserContext} (it owns
 * identity). A dependency-light default reads it from the current web request —
 * set the {@code rbac.userId} / {@code rbac.profiles} request attributes (e.g. in
 * a filter after validating the token against the IDM) — or provide your own
 * {@link UserContextResolver} bean to override.
 */
// Order AFTER the infrastructure auto-configurations: the @ConditionalOnBean checks
// below (StringRedisTemplate, ConnectionFactory) are evaluated when this class's
// bean definitions are registered, so those bean definitions must already exist.
// Without this ordering the L2 cache / invalidation subscriber silently vanish
// depending on classpath order (see InvalidationSubscriberIntegrationTest).
@AutoConfiguration(after = {RedisAutoConfiguration.class, RabbitAutoConfiguration.class})
@EnableConfigurationProperties(RbacClientProperties.class)
@ConditionalOnProperty(prefix = "rbac.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RbacClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public L1Cache rbacL1Cache(RbacClientProperties props) {
        return new L1Cache(props.getL1Ttl(), props.getL1MaxSize());
    }

    /**
     * L2 shared cache (Redis), reading via the shared {@link com.example.rbac.model.CacheKeys}
     * scheme (ADR D5). Two mutually-exclusive wirings, selected by {@code rbac.client.redis.host}:
     * <ul>
     *   <li><b>Dedicated</b> — when {@code rbac.client.redis.host} is set, the starter builds its
     *       own connection (see {@link #rbacL2ConnectionFactory}) so it never reads grant sets from
     *       the host app's session/data Redis. Preferred in production.</li>
     *   <li><b>Host fallback</b> — when unset, reuse the host application's
     *       {@code StringRedisTemplate} (historical behavior; fine only when that Redis is the
     *       shared one and read-restricted to {@code rbac:grants:*}).</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean(name = "rbacL2Redis")
    @ConditionalOnClass({StringRedisTemplate.class, LettuceConnectionFactory.class})
    @ConditionalOnExpression("'${rbac.client.redis.host:}' != ''")
    public RbacL2Redis rbacL2Redis(RbacClientProperties props) {
        RbacClientProperties.Redis r = props.getRedis();
        return new RbacL2Redis(r.getHost(), r.getPort(), r.getPassword(), r.getDatabase());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({StringRedisTemplate.class, LettuceConnectionFactory.class})
    @ConditionalOnExpression("'${rbac.client.redis.host:}' != ''")
    public L2Cache rbacL2CacheDedicated(RbacL2Redis dedicated, ObjectMapper json) {
        // RbacL2Redis owns a private connection (its own LettuceConnectionFactory + template)
        // and is a DisposableBean, so the pool is closed on shutdown. It implements none of
        // the Redis factory interfaces, so it cannot be picked up by the host's Redis
        // auto-configuration or type-based autowiring.
        return new L2Cache(dedicated.template(), json);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnExpression("'${rbac.client.redis.host:}' == ''")
    public L2Cache rbacL2Cache(StringRedisTemplate redis, ObjectMapper json) {
        return new L2Cache(redis, json);
    }

    /**
     * Metrics sink: a Micrometer adapter when the host app has a
     * {@code MeterRegistry} bean, otherwise a no-op. The client has no hard
     * dependency on Micrometer — an L1-only host without actuator still boots
     * (ADR D5). A host app can override by providing its own
     * {@link RbacClientMetrics} bean.
     *
     * <p>{@code @ConditionalOnClass} (not a {@code MeterRegistry} method parameter)
     * so the micrometer classes are only referenced — and thus only loaded — when
     * they are actually present.
     */
    @Bean
    @ConditionalOnMissingBean(RbacClientMetrics.class)
    @ConditionalOnClass(MeterRegistry.class)
    public RbacClientMetrics rbacClientMetrics(ConfigurableListableBeanFactory beanFactory) {
        MeterRegistry registry = beanFactory.getBeansOfType(MeterRegistry.class)
                .values().stream().findFirst().orElse(null);
        return (registry == null) ? RbacClientMetrics.NONE : new MicrometerRbacClientMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(AccessClient.class)
    public AccessClient rbacAccessClient(L1Cache l1,
                                         ObjectProvider<L2Cache> l2,
                                         RbacClientProperties props,
                                         ObjectMapper json,
                                         RbacClientMetrics metrics) {
        // Build our own RestClient so we never depend on (or clash with) a
        // host-app RestClient bean. Connect + read timeouts so a hung PDP cannot
        // hold request threads indefinitely (REVIEW #10).
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        RestClient rest = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        return new DefaultAccessClient(l1, l2.getIfAvailable(), rest, json, metrics);
    }

    @Bean
    @ConditionalOnClass(ConnectionFactory.class)
    @ConditionalOnBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "rbac.client", name = "invalidation-enabled", havingValue = "true", matchIfMissing = true)
    public InvalidationSubscriber rbacInvalidationSubscriber(ConnectionFactory connectionFactory,
                                                             RbacClientProperties props,
                                                             ObjectMapper json,
                                                             AccessClient accessClient,
                                                             RbacClientMetrics metrics) {
        // Broadcast pattern: a unique queue per instance so the exchange fans each
        // event out to EVERY replica. A shared queue would competitive-consume and
        // deliver each event to only one replica, defeating D6 ("reach every replica").
        // A lost message only degrades a replica to its TTL bound — the version check
        // is the real guarantee — so per-instance auto-delete queues are safe.
        String queue = props.getInvalidationQueue();
        if (queue == null || queue.isBlank()) {
            queue = com.example.rbac.model.Bus.DEFAULT_QUEUE + "."
                    + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return new InvalidationSubscriber(
                connectionFactory,
                queue,
                json,
                event -> {
                    metrics.invalidationEvent();
                    if (accessClient instanceof InvalidationHandler handler) {
                        handler.onEvent(event);
                    }
                });
    }

    @Bean
    @ConditionalOnMissingBean
    public UserContextResolver rbacUserContextResolver() {
        return () -> {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                throw new IllegalStateException(
                        "No active web request; set the 'rbac.userId'/'rbac.profiles' request attributes "
                                + "or provide your own UserContextResolver bean.");
            }
            Object userId = attrs.getAttribute("rbac.userId", RequestAttributes.SCOPE_REQUEST);
            @SuppressWarnings("unchecked")
            Set<String> profiles = (Set<String>) attrs.getAttribute("rbac.profiles", RequestAttributes.SCOPE_REQUEST);
            return new UserContext(userId == null ? null : userId.toString(),
                    profiles == null ? Set.of() : profiles);
        };
    }
}
