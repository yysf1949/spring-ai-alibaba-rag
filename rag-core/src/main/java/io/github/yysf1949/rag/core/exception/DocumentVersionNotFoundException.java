package io.github.yysf1949.rag.core.exception;

/**
 * Thrown when an operation refers to a document version that does not exist
 * in the underlying store. Companion to
 * {@link KbVersionNotFoundException} (Phase 18 P2) but at the
 * {@code (tenantId, kbId, docId, versionId)} granularity.
 */
public class DocumentVersionNotFoundException extends RuntimeException {

    private final String tenantId;
    private final String kbId;
    private final String docId;
    private final long versionId;

    public DocumentVersionNotFoundException(String tenantId, String kbId, String docId, long versionId) {
        super("Document version not found: tenantId=" + tenantId
              + ", kbId=" + kbId
              + ", docId=" + docId
              + ", versionId=" + versionId);
        this.tenantId = tenantId;
        this.kbId = kbId;
        this.docId = docId;
        this.versionId = versionId;
    }

    public String tenantId() { return tenantId; }
    public String kbId() { return kbId; }
    public String docId() { return docId; }
    public long versionId() { return versionId; }
}
