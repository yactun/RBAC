package com.example.rbac.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared cache-key scheme. BOTH the PDP (writer) and the starter (reader) must
 * derive the exact same key for the same profileSet, so it lives here in the
 * dependency-free model module.
 *
 * <p>Since D1 (REVIEW, 2026-10-20) the key is derived from the <b>profile set
 * alone</b> — not from the user. Decisions are pure RBAC on the profile set (ADR
 * D1/D2), so the {@code GrantSet} is identical for every user holding the same
 * profiles: keying per user would fragment the same payload into up to 15k Redis
 * entries instead of a few dozen. The request still carries {@code userId} for
 * AUDIT (PDP-side) — it is deliberately excluded from the cache key. Entries
 * written under the old per-user scheme orphan and expire by TTL (self-heal).
 */
public final class CacheKeys {

    /** Global policy-version key (Redis). Bumped on every grant change. */
    public static final String POLICY_VERSION = "rbac:policy:version";

    private CacheKeys() {
    }

    /** Redis key for the full grant set of a profile set. */
    public static String grantsKey(Set<String> profiles) {
        return "rbac:grants:" + digest(profiles);
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
