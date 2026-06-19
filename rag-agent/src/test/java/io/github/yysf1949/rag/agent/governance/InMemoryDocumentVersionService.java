package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link DocumentVersionService} for integration tests.
 *
 * <p>Uses {@code (tenantId, kbId, docId, versionId)} as the composite key.
 * Thread-safe via {@link ConcurrentHashMap}.</p>
 */
class InMemoryDocumentVersionService implements DocumentVersionService {

    /** Key: "tenantId|kbId|docId|versionId" → metadata */
    private final Map<String, DocumentVersionMeta> versions = new ConcurrentHashMap<>();
    /** Key: "tenantId|kbId|docId" → active version id */
    private final Map<String, Long> activeVersions = new ConcurrentHashMap<>();

    @Override
    public List<DocumentVersionMeta> listVersions(String tenantId, String kbId, String docId) {
        String prefix = tenantId + "|" + kbId + "|" + docId + "|";
        return versions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingLong(DocumentVersionMeta::versionId).reversed())
                .toList();
    }

    @Override
    public Optional<Long> getActiveVersion(String tenantId, String kbId, String docId) {
        return Optional.ofNullable(activeVersions.get(tenantId + "|" + kbId + "|" + docId));
    }

    @Override
    public DocumentVersionMeta publish(String tenantId, String kbId, String docId,
                                        long versionId, String sourceLabel) {
        String key = key(tenantId, kbId, docId, versionId);
        DocumentVersionMeta existing = versions.get(key);
        if (existing == null) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, versionId);
        }
        String activeKey = tenantId + "|" + kbId + "|" + docId;
        Long currentActive = activeVersions.get(activeKey);
        if (currentActive != null && currentActive == versionId) {
            return existing; // idempotent
        }
        // Deprecate current active
        if (currentActive != null) {
            String oldKey = key(tenantId, kbId, docId, currentActive);
            DocumentVersionMeta old = versions.get(oldKey);
            if (old != null) {
                versions.put(oldKey, new DocumentVersionMeta(
                        old.versionId(), old.docId(), DocumentVersionMeta.Status.DEPRECATED,
                        old.createdAt(), old.publishedAt(), old.chunkCount(), old.sourceLabel()));
            }
        }
        // Make target active
        DocumentVersionMeta updated = new DocumentVersionMeta(
                existing.versionId(), existing.docId(), DocumentVersionMeta.Status.ACTIVE,
                existing.createdAt(), Instant.now(), existing.chunkCount(),
                sourceLabel != null ? sourceLabel : existing.sourceLabel());
        versions.put(key, updated);
        activeVersions.put(activeKey, versionId);
        return updated;
    }

    @Override
    public DocumentVersionMeta rollback(String tenantId, String kbId, String docId, long targetVersion) {
        return publish(tenantId, kbId, docId, targetVersion, null);
    }

    @Override
    public long resolveVersion(String tenantId, String kbId, String docId, long requested) {
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId, docId).orElseThrow(() ->
                    new DocumentVersionNotFoundException(tenantId, kbId, docId, requested));
        }
        if (versions.get(key(tenantId, kbId, docId, requested)) == null) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, requested);
        }
        return requested;
    }

    @Override
    public DocumentVersionMeta registerVersion(String tenantId, String kbId, String docId,
                                                long versionId, DocumentVersionMeta.Status initialStatus,
                                                String sourceLabel, int chunkCount) {
        String key = key(tenantId, kbId, docId, versionId);
        if (versions.containsKey(key)) {
            return versions.get(key); // idempotent
        }
        DocumentVersionMeta meta = new DocumentVersionMeta(
                versionId, docId,
                initialStatus != null ? initialStatus : DocumentVersionMeta.Status.DRAFT,
                Instant.now(), null, chunkCount, sourceLabel);
        versions.put(key, meta);
        return meta;
    }

    private static String key(String tenantId, String kbId, String docId, long versionId) {
        return tenantId + "|" + kbId + "|" + docId + "|" + versionId;
    }
}
