package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Generic ANSI-SQL {@link DocumentVersionService} — safety net for backends
 * without first-class support (PostgreSQL, Oracle, SQL Server).
 *
 * <p>Companion to {@link JdbcKbVersionService} (Phase 18 P2) but
 * <strong>per-document</strong> rather than per-KB. Primary key is
 * {@code (tenant_id, kb_id, doc_id, version_id)}.</p>
 *
 * <p>Subclasses set DDL string returned by {@link #schemaDdl()} and
 * {@link #activeSchemaDdl()}.</p>
 */
public abstract class JdbcDocumentVersionService implements DocumentVersionService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final DataSource dataSource;

    protected JdbcDocumentVersionService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    protected abstract String schemaDdl();

    protected abstract String activeSchemaDdl();

    public final void ensureSchema() {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(schemaDdl());
            s.execute(activeSchemaDdl());
            log.info("{} schema ready (tables=kb_doc_version,kb_doc_active_version)",
                    getClass().getSimpleName());
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create kb_doc_version schema", ex);
        }
    }

    @Override
    public final List<DocumentVersionMeta> listVersions(String tenantId, String kbId, String docId) {
        validate(tenantId, kbId, docId);
        String sql = "SELECT version_id, status, created_at, published_at, chunk_count, source_label "
                   + "FROM kb_doc_version WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? "
                   + "ORDER BY version_id DESC";
        List<DocumentVersionMeta> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readMeta(rs, docId));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("listVersions failed", ex);
        }
        return out;
    }

    @Override
    public final Optional<Long> getActiveVersion(String tenantId, String kbId, String docId) {
        validate(tenantId, kbId, docId);
        String sql = "SELECT version_id FROM kb_doc_active_version "
                   + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("getActiveVersion failed", ex);
        }
    }

    @Override
    public final DocumentVersionMeta publish(String tenantId, String kbId, String docId,
                                              long versionId, String sourceLabel) {
        validate(tenantId, kbId, docId);
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
        Instant now = Instant.now();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long existing = getActiveVersionNoCheck(tenantId, kbId, docId);
                if (existing == versionId) {
                    // Truly idempotent: do NOT overwrite published_at; only
                    // fill in source_label if it was previously null.
                    try (PreparedStatement ts = c.prepareStatement(
                            "UPDATE kb_doc_version SET source_label = COALESCE(?, source_label) "
                          + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? AND version_id = ?")) {
                        if (sourceLabel == null) ts.setNull(1, Types.VARCHAR);
                        else ts.setString(1, sourceLabel);
                        ts.setString(2, tenantId);
                        ts.setString(3, kbId);
                        ts.setString(4, docId);
                        ts.setLong(5, versionId);
                        ts.executeUpdate();
                    }
                    c.commit();
                    return readSingle(tenantId, kbId, docId, versionId);
                }
                if (existing > 0) {
                    try (PreparedStatement dep = c.prepareStatement(
                            "UPDATE kb_doc_version SET status = 'DEPRECATED' "
                          + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? "
                          + "AND version_id = ? AND status = 'ACTIVE'")) {
                        dep.setString(1, tenantId);
                        dep.setString(2, kbId);
                        dep.setString(3, docId);
                        dep.setLong(4, existing);
                        dep.executeUpdate();
                    }
                }
                int updated;
                try (PreparedStatement act = c.prepareStatement(
                        "UPDATE kb_doc_version SET status = 'ACTIVE', published_at = ?, "
                      + "source_label = COALESCE(?, source_label) "
                      + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? AND version_id = ?")) {
                    act.setTimestamp(1, Timestamp.from(now));
                    if (sourceLabel == null) act.setNull(2, Types.VARCHAR);
                    else act.setString(2, sourceLabel);
                    act.setString(3, tenantId);
                    act.setString(4, kbId);
                    act.setString(5, docId);
                    act.setLong(6, versionId);
                    updated = act.executeUpdate();
                }
                if (updated == 0) {
                    c.rollback();
                    throw new DocumentVersionNotFoundException(tenantId, kbId, docId, versionId);
                }
                // Portable upsert into active pointer table.
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT 1 FROM kb_doc_active_version "
                      + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ?")) {
                    sel.setString(1, tenantId);
                    sel.setString(2, kbId);
                    sel.setString(3, docId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            try (PreparedStatement upd = c.prepareStatement(
                                    "UPDATE kb_doc_active_version SET version_id = ? "
                                  + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ?")) {
                                upd.setLong(1, versionId);
                                upd.setString(2, tenantId);
                                upd.setString(3, kbId);
                                upd.setString(4, docId);
                                upd.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement ins = c.prepareStatement(
                                    "INSERT INTO kb_doc_active_version (tenant_id, kb_id, doc_id, version_id) "
                                  + "VALUES (?, ?, ?, ?)")) {
                                ins.setString(1, tenantId);
                                ins.setString(2, kbId);
                                ins.setString(3, docId);
                                ins.setLong(4, versionId);
                                ins.executeUpdate();
                            }
                        }
                    }
                }
                c.commit();
                log.info("publish: tenant={} kb={} doc={} active=version{} (was={})",
                        tenantId, kbId, docId, versionId, existing);
                return readSingle(tenantId, kbId, docId, versionId);
            } catch (SQLException | DocumentVersionNotFoundException ex) {
                c.rollback();
                if (ex instanceof DocumentVersionNotFoundException dnf) throw dnf;
                throw new IllegalStateException("publish failed", ex);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("publish failed", ex);
        }
    }

    @Override
    public final DocumentVersionMeta rollback(String tenantId, String kbId, String docId, long targetVersion) {
        if (!versionExists(tenantId, kbId, docId, targetVersion)) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, targetVersion);
        }
        return publish(tenantId, kbId, docId, targetVersion, null);
    }

    @Override
    public final long resolveVersion(String tenantId, String kbId, String docId, long requested) {
        validate(tenantId, kbId, docId);
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId, docId).orElseThrow(() ->
                    new DocumentVersionNotFoundException(tenantId, kbId, docId, -1L));
        }
        if (!versionExists(tenantId, kbId, docId, requested)) {
            throw new DocumentVersionNotFoundException(tenantId, kbId, docId, requested);
        }
        return requested;
    }

    @Override
    public final DocumentVersionMeta registerVersion(String tenantId, String kbId, String docId,
                                                      long versionId, DocumentVersionMeta.Status initialStatus,
                                                      String sourceLabel, int chunkCount) {
        validate(tenantId, kbId, docId);
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
        Instant now = Instant.now();
        // Idempotent: if the row already exists, first registration wins.
        if (versionExists(tenantId, kbId, docId, versionId)) {
            return readSingle(tenantId, kbId, docId, versionId);
        }
        String sql = "INSERT INTO kb_doc_version "
                   + "(tenant_id, kb_id, doc_id, version_id, status, created_at, published_at, chunk_count, source_label) "
                   + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            ps.setLong(4, versionId);
            ps.setString(5, initialStatus == null ? "DRAFT" : initialStatus.name());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setInt(7, chunkCount);
            if (sourceLabel == null) ps.setNull(8, Types.VARCHAR);
            else ps.setString(8, sourceLabel);
            ps.executeUpdate();
            return readSingle(tenantId, kbId, docId, versionId);
        } catch (SQLException ex) {
            throw new IllegalStateException("registerVersion failed", ex);
        }
    }

    final boolean versionExists(String tenantId, String kbId, String docId, long versionId) {
        String sql = "SELECT 1 FROM kb_doc_version "
                   + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? AND version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            ps.setLong(4, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("versionExists failed", ex);
        }
    }

    private long getActiveVersionNoCheck(String tenantId, String kbId, String docId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version_id FROM kb_doc_active_version "
                   + "WHERE tenant_id = ? AND kb_id = ? AND doc_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("getActiveVersionNoCheck failed", ex);
        }
    }

    private DocumentVersionMeta readSingle(String tenantId, String kbId, String docId, long versionId) {
        String sql = "SELECT version_id, status, created_at, published_at, chunk_count, source_label "
                   + "FROM kb_doc_version WHERE tenant_id = ? AND kb_id = ? AND doc_id = ? AND version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setString(3, docId);
            ps.setLong(4, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new DocumentVersionNotFoundException(tenantId, kbId, docId, versionId);
                }
                return readMeta(rs, docId);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("readSingle failed", ex);
        }
    }

    private DocumentVersionMeta readMeta(ResultSet rs, String docId) throws SQLException {
        long versionId = rs.getLong("version_id");
        String statusStr = rs.getString("status");
        DocumentVersionMeta.Status status;
        try {
            status = DocumentVersionMeta.Status.valueOf(statusStr);
        } catch (IllegalArgumentException ex) {
            status = DocumentVersionMeta.Status.ACTIVE;
        }
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp pubTs = rs.getTimestamp("published_at");
        Instant publishedAt = pubTs == null ? null : pubTs.toInstant();
        int chunkCount = rs.getInt("chunk_count");
        String sourceLabel = rs.getString("source_label");
        return new DocumentVersionMeta(versionId, docId, status, createdAt, publishedAt,
                chunkCount, sourceLabel);
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
}
