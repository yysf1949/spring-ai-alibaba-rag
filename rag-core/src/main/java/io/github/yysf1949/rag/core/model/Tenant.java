package io.github.yysf1949.rag.core.model;

import java.util.Objects;

/**
 * Tenant identity — the hard wall across which no query or chunk may leak.
 *
 * <p>Design spec §8.1 — every retrieval call enforces
 * {@code chunk.tenantId == query.tenantId} <strong>before</strong> any other
 * filter (kb / status / permission).</p>
 */
public record Tenant(String tenantId) {

    public Tenant {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    public static Tenant of(String tenantId) {
        return new Tenant(tenantId);
    }
}