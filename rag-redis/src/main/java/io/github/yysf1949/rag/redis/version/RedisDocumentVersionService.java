package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed {@link DocumentVersionService}.
 *
 * <h2>Storage layout (per doc)</h2>
 * <ul>
 *   <li>Active pointer — {@code rag:kb-doc-active:{tenantId}:{kbId}:{docId}},
 *       a string value containing the currently-active version id.</li>
 *   <li>Per-version metadata —
 *       {@code rag:kb-doc-version-meta:{tenantId}:{kbId}:{docId}:{versionId}},
 *       a Redis hash with fields
 *       {@code status, createdAt, publishedAt, chunkCount, sourceLabel}.</li>
 *   <li>Version ZSET — {@code rag:kb-doc-versions:{tenantId}:{kbId}:{docId}},
 *       scored by versionId (lets us list newest first via {@code zrevrange}).
 *       Compared to P2 SET-per-KB, the ZSET gives us natural newest-first
 *       ordering without an extra sort.</li>
 * </ul>
 *
 * <h2>Why a separate active pointer per doc (not nested in hash)</h2>
 * <p>A single {@code GET} on a string key is faster than a hash {@code HGET},
 * and the active-pointer key is the most-read object in the hot retrieval
 * path. String-key access is also trivially safe under concurrent
 * {@code publish} calls (single-key atomic).</p>
 */
public class RedisDocumentVersionService implements DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(RedisDocumentVersionService.class);

    private final UnifiedJedis jedis;

    public RedisDocumentVersionService(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    private static String metaKey(String tenantId, String kbId, String docId, long versionId) {
        return "rag:kb-doc-version-meta:" + tenantId + ":" + kbId + ":" + docId + ":" + versionId;
    }

    private static String versionsZsetKey(String tenantId, String kbId, String docId) {
        return "rag:kb-doc-versions:" + tenantId + ":" + kbId + ":" + docId;
    }

    private static String activePointerKey(String tenantId, String kbId, String docId) {
        return "rag:kb-doc-active:" + tenantId + ":" + kbId + ":" + docId;
    }

    @Override
    public List<DocumentVersionMeta> listVersions(String tenantId, String kbId, String docId) {
        validate(tenantId, kbId, docId);
        List<String> versions = jedis.zrevrange(versionsZsetKey(tenantId, kbId, docId), 0, -1);
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        List<DocumentVersionMeta> out = new ArrayList<>(versions.size());
        for (String v : versions) {
            try {
                long versionId = Long.parseLong(v);
                readMeta(tenantId, kbId, docId, versionId).ifPresent(out::add);
            } catch (NumberFormatException ignored) {
                // skip non-numeric entries (defensive)
            }
        }
        return out;
    }

    @Override
    public Optional<Long> getActiveVersion(String tenantId, String kbId, String docId) {
        validate(tenantId, kbId, docId);
        String raw = jedis.get(activePointerKey(tenantId, kbId, docId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            long v = Long.parseLong(raw);
            return v > 0 ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException ex) {
            log.warn("active pointer corrupt for tenant={} kb={} doc={} (value={})",
                    tenantId, kbId, docId, raw);
            return Optional.empty();
        }
    }

    @Override
    public DocumentVersionMeta publish(String tenantId, String kbId, String docId,
                                       long versionId, String sourceLabel) {
        validateAll(tenantId, kbId, docId, versionId);
        Optional<Long> existing = getActiveVersion(tenantId, kbId, docId);
        Instant now = Instant.now();

        // Idempotent re-publish: still update publishedAt and sourceLabel.
        if (existing.isPresent() && existing.get() == versionId) {
            Map<String, String> updates = new HashMap<>();
            updates.put("publishedAt", now.toString());
            if (sourceLabel != null) {
                updates.put("sourceLabel", sourceLabel);
            }
            jedis.hset(metaKey(tenantId, kbId, docId, versionId), updates);
            jedis.zadd(versionsZsetKey(tenantId, kbId, docId), versionId, String.valueOf(versionId));
            log.info("publish (idempotent): tenant={} kb={} doc={} active=version{}",
                    tenantId, kbId, docId, versionId);
            return readMeta(tenantId, kbId, docId, versionId)
                    .orElseThrow(() -> new DocumentVersionNotFoundException(tenantId, kbId, docId, versionId));
        }

        // First-time or rollback: ensure meta hash exists, then promote.
        ensureMetaExists(tenantId, kbId, docId, versionId, DocumentVersionMeta.Status.DRAFT);
        existing.ifPresent(prev -> setStatus(tenantId, kbId, docId, prev, DocumentVersionMeta.Status.DEPRECATED));

        Map<String, String> updates = new HashMap<>();
        updates.put("status", DocumentVersionMeta.Status.ACTIVE.name());
        updates.put("publishedAt", now.toString());
        if (sourceLabel != null) {
            updates.put("sourceLabel", sourceLabel);
        }
        try {
            jedis.hset(metaKey(tenantId, kbId, docId, versionId), updates);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("publish hset failed", ex);
        }
        try {
            jedis.set(activePointerKey(tenantId, kbId, docId), String.valueOf(versionId));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("active pointer set failed", ex);
        }
        jedis.zadd(versionsZsetKey(tenantId, kbId, docId), versionId, String.valueOf(versionId));

        log.info("publish: tenant={} kb={} doc={} active=version{} (was={})",
                tenantId, kbId, docId, versionId, existing.orElse(0L));
        return readMeta(tenantId, kbId, docId, versionId)
                .orElseThrow(() -> new DocumentVersionNotFoundException(tenantId, kbId, docId, versionId));
    }

    @Override
    public DocumentVersionMeta rollback(String tenantId, String kbId, String docId, long targetVersion) {
        validateAll(tenantId, kbId, docId, targetVersion);
        if (readMeta(tenantId, kbId, docId, targetVersion).isEmpty()) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, targetVersion);
        }
        return publish(tenantId, kbId, docId, targetVersion, null);
    }

    @Override
    public long resolveVersion(String tenantId, String kbId, String docId, long requested) {
        validate(tenantId, kbId, docId);
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId, docId).orElseThrow(() ->
                    new DocumentVersionNotFoundException(tenantId, kbId, docId, -1L));
        }
        if (readMeta(tenantId, kbId, docId, requested).isEmpty()) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, requested);
        }
        return requested;
    }

    @Override
    public DocumentVersionMeta registerVersion(String tenantId, String kbId, String docId,
                                               long versionId, DocumentVersionMeta.Status initialStatus,
                                               String sourceLabel, int chunkCount) {
        validateAll(tenantId, kbId, docId, versionId);
        Instant now = Instant.now();
        Map<String, String> initial = new HashMap<>();
        initial.put("status", (initialStatus == null ? DocumentVersionMeta.Status.DRAFT : initialStatus).name());
        initial.put("createdAt", now.toString());
        initial.put("chunkCount", String.valueOf(chunkCount));
        if (sourceLabel != null) {
            initial.put("sourceLabel", sourceLabel);
        }
        // First-wins idempotency: if meta already exists, skip.
        if (jedis.exists(metaKey(tenantId, kbId, docId, versionId))) {
            jedis.zadd(versionsZsetKey(tenantId, kbId, docId), versionId, String.valueOf(versionId));
            return readMeta(tenantId, kbId, docId, versionId).orElseThrow();
        }
        jedis.hset(metaKey(tenantId, kbId, docId, versionId), initial);
        jedis.zadd(versionsZsetKey(tenantId, kbId, docId), versionId, String.valueOf(versionId));
        return new DocumentVersionMeta(versionId, docId,
                initialStatus == null ? DocumentVersionMeta.Status.DRAFT : initialStatus,
                now, null, chunkCount, sourceLabel);
    }

    private void ensureMetaExists(String tenantId, String kbId, String docId, long versionId,
                                  DocumentVersionMeta.Status initialStatus) {
        String key = metaKey(tenantId, kbId, docId, versionId);
        if (jedis.exists(key)) return;
        Map<String, String> initial = new HashMap<>();
        initial.put("status", initialStatus.name());
        initial.put("createdAt", Instant.now().toString());
        initial.put("chunkCount", "0");
        jedis.hset(key, initial);
        jedis.zadd(versionsZsetKey(tenantId, kbId, docId), versionId, String.valueOf(versionId));
    }

    private void setStatus(String tenantId, String kbId, String docId, long versionId,
                           DocumentVersionMeta.Status status) {
        // 3-arg overload exists in jedis 5.2.0 for single-field updates.
        jedis.hset(metaKey(tenantId, kbId, docId, versionId), "status", status.name());
    }

    private Optional<DocumentVersionMeta> readMeta(String tenantId, String kbId, String docId, long versionId) {
        String key = metaKey(tenantId, kbId, docId, versionId);
        if (!jedis.exists(key)) {
            return Optional.empty();
        }
        Map<String, String> hash = jedis.hgetAll(key);
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        String statusStr = hash.getOrDefault("status", "DRAFT");
        DocumentVersionMeta.Status status;
        try {
            status = DocumentVersionMeta.Status.valueOf(statusStr);
        } catch (IllegalArgumentException ex) {
            status = DocumentVersionMeta.Status.ACTIVE;
        }
        Instant createdAt = parseInstant(hash.get("createdAt"), Instant.EPOCH);
        Instant publishedAt = parseInstant(hash.get("publishedAt"), null);
        int chunkCount = parseInt(hash.get("chunkCount"), 0);
        String sourceLabel = hash.get("sourceLabel");
        return Optional.of(new DocumentVersionMeta(versionId, docId, status, createdAt, publishedAt,
                chunkCount, sourceLabel));
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

    private void validate(String tenantId, String kbId, String docId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
    }

    private void validateAll(String tenantId, String kbId, String docId, long versionId) {
        validate(tenantId, kbId, docId);
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
    }

    // visible for tests: expose keys
    static String _testMetaKey(String t, String k, String d, long v) { return metaKey(t, k, d, v); }
    static String _testZsetKey(String t, String k, String d) { return versionsZsetKey(t, k, d); }
    static String _testActiveKey(String t, String k, String d) { return activePointerKey(t, k, d); }
}
