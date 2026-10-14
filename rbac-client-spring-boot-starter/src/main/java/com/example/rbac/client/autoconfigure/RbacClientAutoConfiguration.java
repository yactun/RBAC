package com.example.rbac.client.autoconfigure;

import com.example.rbac.client.AccessClient;
import com.example.rbac.client.DefaultAccessClient;
import com.example.rbac.client.RbacClientProperties;
import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.invalidation.InvalidationHandler;
import com.example.rbac.client.invalidation.InvalidationSubscriber;
import com.example.rbac.client.security.UserContextResolver;
import com.example.rbac.model.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
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
@AutoConfiguration
@EnableConfigurationProperties(RbacClientProperties.class)
@ConditionalOnProperty(prefix = "rbac.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RbacClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public L1Cache rbacL1Cache(RbacClientProperties props) {
        return new L1Cache(props.getL1Ttl(), props.getL1MaxSize());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public L2Cache rbacL2Cache(StringRedisTemplate redis, ObjectMapper json) {
        return new L2Cache(redis, json);
    }

    @Bean
    @ConditionalOnMissingBean(AccessClient.class)
    public AccessClient rbacAccessClient(L1Cache l1,
                                         ObjectProvider<L2Cache> l2,
                                         RbacClientProperties props,
                                         ObjectMapper json) {
        // Build our own RestClient so we never depend on (or clash with) a
        // host-app RestClient bean.
        RestClient rest = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        return new DefaultAccessClient(l1, l2.getIfAvailable(), rest, json);
    }

    @Bean
    @ConditionalOnClass(ConnectionFactory.class)
    @ConditionalOnBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "rbac.client", name = "invalidation-enabled", havingValue = "true", matchIfMissing = true)
    public InvalidationSubscriber rbacInvalidationSubscriber(ConnectionFactory connectionFactory,
                                                             RbacClientProperties props,
                                                             ObjectMapper json,
                                                             AccessClient accessClient) {
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
