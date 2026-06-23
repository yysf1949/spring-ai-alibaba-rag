package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link KbVersionService} for integration tests.
 *
 * <p>Uses {@code (tenantId, kbId, versionId)} as the composite key.
 * Thread-safe via {@link ConcurrentHashMap}.</p>
 */
class InMemoryKbVersionService implements KbVersionService {

    /** Key: "tenantId|kbId|versionId" → metadata */
    private final Map<String, KbVersionMeta> versions = new ConcurrentHashMap<>();
    /** Key: "tenantId|kbId" → active version id */
    private final Map<String, Long> activeVersions = new ConcurrentHashMap<>();

    @Override
    public List<KbVersionMeta> listVersions(String tenantId, String kbId) {
        String prefix = tenantId + "|" + kbId + "|";
        return versions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingLong(KbVersionMeta::versionId).reversed())
                .toList();
    }

    @Override
    public Optional<Long> getActiveVersion(String tenantId, String kbId) {
        return Optional.ofNullable(activeVersions.get(tenantId + "|" + kbId));
    }

    @Override
    public void publish(String tenantId, String kbId, long versionId) {
        String key = key(tenantId, kbId, versionId);
        KbVersionMeta existing = versions.get(key);
        if (existing == null) {
            throw new KbVersionNotFoundException(
                    "kb_version row missing: tenant=" + tenantId + " kb=" + kbId
                    + " version=" + versionId);
        }
        String activeKey = tenantId + "|" + kbId;
        Long currentActive = activeVersions.get(activeKey);
        if (currentActive != null && currentActive == versionId) {
            return; // idempotent
        }
        // Deprecate current active
        if (currentActive != null) {
            String oldKey = key(tenantId, kbId, currentActive);
            KbVersionMeta old = versions.get(oldKey);
            if (old != null) {
                versions.put(oldKey, new KbVersionMeta(
                        old.versionId(), KbVersionMeta.Status.DEPRECATED,
                        old.createdAt(), old.publishedAt(), old.docCount(), old.sourceLabel()));
            }
        }
        // Make target active
        versions.put(key, new KbVersionMeta(
                existing.versionId(), KbVersionMeta.Status.ACTIVE,
                existing.createdAt(), Instant.now(), existing.docCount(), existing.sourceLabel()));
        activeVersions.put(activeKey, versionId);
    }

    @Override
    public void rollback(String tenantId, String kbId, long versionId) {
        publish(tenantId, kbId, versionId);
    }

    @Override
    public long resolveVersion(String tenantId, String kbId, long requested) {
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId).orElseThrow(() ->
                    new KbVersionNotFoundException(
                            "no active version for tenant=" + tenantId + " kb=" + kbId));
        }
        if (versions.get(key(tenantId, kbId, requested)) == null) {
            throw new KbVersionNotFoundException(
                    "version " + requested + " not found for tenant=" + tenantId + " kb=" + kbId);
        }
        return requested;
    }

    @Override
    public void registerVersion(String tenantId, String kbId, long versionId,
                                KbVersionMeta.Status initialStatus, String sourceLabel) {
        String key = key(tenantId, kbId, versionId);
        if (versions.containsKey(key)) {
            return; // idempotent
        }
        versions.put(key, new KbVersionMeta(
                versionId,
                initialStatus != null ? initialStatus : KbVersionMeta.Status.DRAFT,
                Instant.now(), null, 0, sourceLabel));
    }

    private static String key(String tenantId, String kbId, long versionId) {
        return tenantId + "|" + kbId + "|" + versionId;
    }
}
