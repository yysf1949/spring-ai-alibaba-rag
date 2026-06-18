package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import io.github.yysf1949.rag.redis.vector.RedisIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed {@link KbVersionService}.
 *
 * <h2>Storage layout</h2>
 * <ul>
 *   <li>Active pointer — reuses
 *       {@link RedisIndexManager#publishPointerKey(String, String)} =
 *       {@code rag:publish:{tenantId}:{kbId}}, a string value containing the
 *       currently-active version id (set by
 *       {@link io.github.yysf1949.rag.redis.vector.RedisVectorStore#publish}).</li>
 *   <li>Per-version metadata —
 *       {@code rag:kb-version-meta:{tenantId}:{kbId}:{versionId}}, a Redis
 *       hash with fields {@code status, createdAt, publishedAt, docCount, sourceLabel}.</li>
 *   <li>Version set — {@code rag:kb-versions:{tenantId}:{kbId}}, a Redis SET
 *       listing known version ids. Used by {@link #listVersions}.</li>
 * </ul>
 *
 * <h2>Why two separate stores (active pointer + metadata)</h2>
 * <p>The active pointer key/value is single-purpose and already used by
 * {@code RedisVectorStore.resolveActiveVersion}; rewriting it would mean
 * changing {@code RedisVectorStore} too. Metadata goes in a hash so we can
 * query individual fields without parsing JSON, and so TTL on the hash
 * doesn't affect the active pointer.</p>
 */
public class RedisKbVersionService implements KbVersionService {

    private static final Logger log = LoggerFactory.getLogger(RedisKbVersionService.class);

    private final UnifiedJedis jedis;
    private final RedisIndexManager indexManager;

    public RedisKbVersionService(UnifiedJedis jedis, RedisIndexManager indexManager) {
        this.jedis = jedis;
        this.indexManager = indexManager;
    }

    private static String metaKey(String tenantId, String kbId, long versionId) {
        return "rag:kb-version-meta:" + tenantId + ":" + kbId + ":" + versionId;
    }

    private static String versionsKey(String tenantId, String kbId) {
        return "rag:kb-versions:" + tenantId + ":" + kbId;
    }

    @Override
    public List<KbVersionMeta> listVersions(String tenantId, String kbId) {
        validateTenantKb(tenantId, kbId);
        Set<String> versions = jedis.smembers(versionsKey(tenantId, kbId));
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        List<Long> sorted = new ArrayList<>(versions.size());
        for (String v : versions) {
            try {
                sorted.add(Long.parseLong(v));
            } catch (NumberFormatException ignored) {
                // skip non-numeric entries (defensive — only happens if some
                // other code path writes non-version keys to this set)
            }
        }
        sorted.sort((a, b) -> Long.compare(b, a)); // newest first
        List<KbVersionMeta> out = new ArrayList<>(sorted.size());
        for (long v : sorted) {
            readMeta(tenantId, kbId, v).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public Optional<Long> getActiveVersion(String tenantId, String kbId) {
        validateTenantKb(tenantId, kbId);
        String raw = jedis.get(indexManager.publishPointerKey(tenantId, kbId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            long v = Long.parseLong(raw);
            return v > 0 ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException ex) {
            log.warn("publish pointer corrupt for tenant={} kb={} (value={})",
                    tenantId, kbId, raw);
            return Optional.empty();
        }
    }

    @Override
    public void publish(String tenantId, String kbId, long versionId) {
        validateAll(tenantId, kbId, versionId);
        Optional<Long> existing = getActiveVersion(tenantId, kbId);
        if (existing.isPresent() && existing.get() == versionId) {
            return; // idempotent
        }
        // Ensure metadata exists for the target version. If not registered, do it now.
        ensureMetaExists(tenantId, kbId, versionId, KbVersionMeta.Status.STAGING);
        // Deprecate previously-active version (if any).
        existing.ifPresent(prev -> setStatus(tenantId, kbId, prev, KbVersionMeta.Status.DEPRECATED));
        // Promote target to ACTIVE.
        Instant now = Instant.now();
        try {
            java.util.Map<String, String> updates = new java.util.HashMap<>();
            updates.put("status", KbVersionMeta.Status.ACTIVE.name());
            updates.put("publishedAt", now.toString());
            jedis.hset(metaKey(tenantId, kbId, versionId), updates);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("publish hset failed", ex);
        }
        // Update active pointer (re-uses RedisVectorStore's key).
        try {
            jedis.set(indexManager.publishPointerKey(tenantId, kbId), String.valueOf(versionId));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("publish pointer set failed", ex);
        }
        log.info("publish: tenant={} kb={} active=version{} (was={})",
                tenantId, kbId, versionId, existing.orElse(0L));
    }

    @Override
    public void rollback(String tenantId, String kbId, long versionId) {
        // Same as publish — Redis has no separate rollback path.
        // Verify the version actually has metadata (otherwise publishing would
        // silently create a zero-data version).
        if (readMeta(tenantId, kbId, versionId).isEmpty()) {
            throw new KbVersionNotFoundException(
                    "version " + versionId + " not found for tenant=" + tenantId + " kb=" + kbId);
        }
        publish(tenantId, kbId, versionId);
    }

    @Override
    public long resolveVersion(String tenantId, String kbId, long requested) {
        validateTenantKb(tenantId, kbId);
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId).orElseThrow(() ->
                    new KbVersionNotFoundException(
                            "no active version for tenant=" + tenantId + " kb=" + kbId));
        }
        if (readMeta(tenantId, kbId, requested).isEmpty()) {
            throw new KbVersionNotFoundException(
                    "version " + requested + " not found for tenant=" + tenantId + " kb=" + kbId);
        }
        return requested;
    }

    // ---- package-private helpers used by tests / sibling stores ------------

    @Override
    public void registerVersion(String tenantId, String kbId, long versionId,
                                KbVersionMeta.Status initialStatus, String sourceLabel) {
        validateAll(tenantId, kbId, versionId);
        ensureMetaExists(tenantId, kbId, versionId,
                initialStatus == null ? KbVersionMeta.Status.DRAFT : initialStatus);
        if (sourceLabel != null) {
            jedis.hset(metaKey(tenantId, kbId, versionId), "sourceLabel", sourceLabel);
        }
        jedis.sadd(versionsKey(tenantId, kbId), String.valueOf(versionId));
    }

    private void ensureMetaExists(String tenantId, String kbId, long versionId,
                                  KbVersionMeta.Status initialStatus) {
        String key = metaKey(tenantId, kbId, versionId);
        boolean exists = jedis.exists(key);
        if (!exists) {
            java.util.Map<String, String> initial = new java.util.HashMap<>();
            initial.put("status", initialStatus.name());
            initial.put("createdAt", Instant.now().toString());
            initial.put("docCount", "0");
            jedis.hset(key, initial);
            jedis.sadd(versionsKey(tenantId, kbId), String.valueOf(versionId));
        }
    }

    private void setStatus(String tenantId, String kbId, long versionId,
                           KbVersionMeta.Status status) {
        // 3-arg overload exists in jedis 5.2.0 for single-field updates.
        jedis.hset(metaKey(tenantId, kbId, versionId), "status", status.name());
    }

    private Optional<KbVersionMeta> readMeta(String tenantId, String kbId, long versionId) {
        String key = metaKey(tenantId, kbId, versionId);
        if (!jedis.exists(key)) {
            return Optional.empty();
        }
        var hash = jedis.hgetAll(key);
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        String statusStr = hash.getOrDefault("status", "DRAFT");
        KbVersionMeta.Status status;
        try {
            status = KbVersionMeta.Status.valueOf(statusStr);
        } catch (IllegalArgumentException ex) {
            status = KbVersionMeta.Status.ACTIVE;
        }
        Instant createdAt = parseInstant(hash.get("createdAt"), Instant.EPOCH);
        Instant publishedAt = parseInstant(hash.get("publishedAt"), null);
        int docCount = parseInt(hash.get("docCount"), 0);
        String sourceLabel = hash.get("sourceLabel");
        return Optional.of(new KbVersionMeta(versionId, status, createdAt, publishedAt,
                docCount, sourceLabel));
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void validateTenantKb(String tenantId, String kbId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
    }

    private void validateAll(String tenantId, String kbId, long versionId) {
        validateTenantKb(tenantId, kbId);
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
    }
}