package com.example.rbac.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Client-side knobs (prefix {@code rbac.client}). */
@ConfigurationProperties(prefix = "rbac.client")
public class RbacClientProperties {

    /** Base URL of the PDP, e.g. http://access-service. */
    private String baseUrl = "http://localhost:8080";

    /** Connect timeout for PDP REST calls — a hung PDP must not hold request threads indefinitely. */
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);

    /** Read (response) timeout for PDP REST calls. */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(5);

    /** L1 (Caffeine) TTL. Bounded staleness fallback when the bus is down. */
    private java.time.Duration l1Ttl = java.time.Duration.ofSeconds(60);

    /** Max entries in the L1 cache. */
    private long l1MaxSize = 20_000;

    /** Enable the RabbitMQ invalidation subscriber (near-instant revocation). */
    private boolean invalidationEnabled = true;

    /** RabbitMQ queue for the invalidation subscriber. Leave {@code null} (default)
     *  to get a unique per-instance auto-delete queue — the correct broadcast pattern
     *  so EVERY replica receives each invalidation. Set it only if you intentionally
     *  want a shared (competitive-consumption) queue across instances. */
    private String invalidationQueue = null;

    /**
     * Dedicated Redis for the shared L2 cache (ADR D5). When {@code host} is set, the
     * starter builds its <b>own</b> {@code StringRedisTemplate} for L2 instead of reusing
     * the host application's one — so the client never reads grant sets from the app's
     * session/data Redis (which it could not trust, and which could write forged
     * {@code GrantSet}s if it shares credentials). Leave {@code host} unset to fall back
     * to the host's {@code StringRedisTemplate} (the historical behavior).
     *
     * <p>Deployment requirement when a shared Redis is used: restrict this connection to
     * <b>read-only</b> on {@code rbac:grants:*} via Redis ACL (the client only reads L2;
     * only the PDP writes it).
     */
    private Redis redis = new Redis();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = (connectTimeout == null) ? java.time.Duration.ofSeconds(2) : connectTimeout;
    }

    public java.time.Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(java.time.Duration readTimeout) {
        this.readTimeout = (readTimeout == null) ? java.time.Duration.ofSeconds(5) : readTimeout;
    }

    public java.time.Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(java.time.Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public boolean isInvalidationEnabled() {
        return invalidationEnabled;
    }

    public void setInvalidationEnabled(boolean invalidationEnabled) {
        this.invalidationEnabled = invalidationEnabled;
    }

    public String getInvalidationQueue() {
        return invalidationQueue;
    }

    public void setInvalidationQueue(String invalidationQueue) {
        this.invalidationQueue = invalidationQueue;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = (redis == null) ? new Redis() : redis;
    }

    /** Connection settings for the starter's dedicated L2 Redis (prefix {@code rbac.client.redis}). */
    public static class Redis {
        /** Redis host. When set (non-blank), the starter uses a dedicated connection for L2. */
        private String host;
        /** Redis port (default 6379). */
        private Integer port = 6379;
        /** Redis password (optional). */
        private String password;
        /** Redis logical database (default 0). */
        private Integer database = 0;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = (port == null) ? 6379 : port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Integer getDatabase() {
            return database;
        }

        public void setDatabase(Integer database) {
            this.database = (database == null) ? 0 : database;
        }
    }
}
