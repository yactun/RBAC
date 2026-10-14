package com.example.rbac.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared cache-key scheme. BOTH the PDP (writer) and the starter (reader) must
 * derive the exact same key for the same (userId, profileSet), so it lives here
 * in the dependency-free model module.
 */
public final class CacheKeys {

    /** Global policy-version key (Redis). Bumped on every grant change. */
    public static final String POLICY_VERSION = "rbac:policy:version";

    private CacheKeys() {
    }

    /** Redis key for the full grant set of a (userId, profileSet) pair. */
    public static String grantsKey(String userId, Set<String> profiles) {
        return "rbac:grants:" + userId + ":" + digest(profiles);
    }

    private static String digest(Set<String> profiles) {
        String s = String.join("\u0000", new TreeSet<>(profiles == null ? Set.of() : profiles));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
