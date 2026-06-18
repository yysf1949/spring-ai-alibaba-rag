package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
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
 * Generic ANSI-SQL {@link KbVersionService} — safety net for backends
 * without first-class support (PostgreSQL, Oracle, SQL Server).
 *
 * <p>Behaviour is identical to {@link H2KbVersionService}; the difference is
 * only the DDL string returned by {@link #schemaDdl()} and
 * {@link #activeSchemaDdl()}. Subclasses set those.</p>
 */
public abstract class JdbcKbVersionService implements KbVersionService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final DataSource dataSource;

    protected JdbcKbVersionService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    protected abstract String schemaDdl();

    protected abstract String activeSchemaDdl();

    public final void ensureSchema() {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(schemaDdl());
            s.execute(activeSchemaDdl());
            log.info("{} schema ready (tables=kb_version,kb_active_version)",
                    getClass().getSimpleName());
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create kb_version schema", ex);
        }
    }

    @Override
    public final List<KbVersionMeta> listVersions(String tenantId, String kbId) {
        validateTenantKb(tenantId, kbId);
        String sql = "SELECT version_id, status, created_at, published_at, doc_count, source_label "
                   + "FROM kb_version WHERE tenant_id = ? AND kb_id = ? ORDER BY version_id DESC";
        List<KbVersionMeta> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readMeta(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("listVersions failed", ex);
        }
        return out;
    }

    @Override
    public final Optional<Long> getActiveVersion(String tenantId, String kbId) {
        validateTenantKb(tenantId, kbId);
        String sql = "SELECT version_id FROM kb_active_version WHERE tenant_id = ? AND kb_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("getActiveVersion failed", ex);
        }
    }

    @Override
    public final void publish(String tenantId, String kbId, long versionId) {
        validateAll(tenantId, kbId, versionId);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long existing = getActiveVersionNoCheck(tenantId, kbId);
                if (existing == versionId) {
                    c.commit();
                    return;
                }
                if (existing > 0) {
                    try (PreparedStatement dep = c.prepareStatement(
                            "UPDATE kb_version SET status = 'DEPRECATED' "
                          + "WHERE tenant_id = ? AND kb_id = ? AND version_id = ? AND status = 'ACTIVE'")) {
                        dep.setString(1, tenantId);
                        dep.setString(2, kbId);
                        dep.setLong(3, existing);
                        dep.executeUpdate();
                    }
                }
                int updated;
                try (PreparedStatement act = c.prepareStatement(
                        "UPDATE kb_version SET status = 'ACTIVE', published_at = ? "
                      + "WHERE tenant_id = ? AND kb_id = ? AND version_id = ?")) {
                    act.setTimestamp(1, Timestamp.from(Instant.now()));
                    act.setString(2, tenantId);
                    act.setString(3, kbId);
                    act.setLong(4, versionId);
                    updated = act.executeUpdate();
                }
                if (updated == 0) {
                    c.rollback();
                    throw new KbVersionNotFoundException(
                            "kb_version row missing: tenant=" + tenantId + " kb=" + kbId
                          + " version=" + versionId);
                }
                // Portable upsert.
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT 1 FROM kb_active_version WHERE tenant_id = ? AND kb_id = ?")) {
                    sel.setString(1, tenantId);
                    sel.setString(2, kbId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            try (PreparedStatement upd = c.prepareStatement(
                                    "UPDATE kb_active_version SET version_id = ? "
                                  + "WHERE tenant_id = ? AND kb_id = ?")) {
                                upd.setLong(1, versionId);
                                upd.setString(2, tenantId);
                                upd.setString(3, kbId);
                                upd.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement ins = c.prepareStatement(
                                    "INSERT INTO kb_active_version (tenant_id, kb_id, version_id) "
                                  + "VALUES (?, ?, ?)")) {
                                ins.setString(1, tenantId);
                                ins.setString(2, kbId);
                                ins.setLong(3, versionId);
                                ins.executeUpdate();
                            }
                        }
                    }
                }
                c.commit();
                log.info("publish: tenant={} kb={} active=version{} (was={})",
                        tenantId, kbId, versionId, existing);
            } catch (SQLException | KbVersionNotFoundException ex) {
                c.rollback();
                throw ex instanceof KbVersionNotFoundException knf
                        ? knf
                        : new IllegalStateException("publish failed", ex);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("publish failed", ex);
        }
    }

    @Override
    public final void rollback(String tenantId, String kbId, long versionId) {
        publish(tenantId, kbId, versionId);
    }

    @Override
    public final long resolveVersion(String tenantId, String kbId, long requested) {
        validateTenantKb(tenantId, kbId);
        if (requested < 0) {
            return getActiveVersion(tenantId, kbId).orElseThrow(() ->
                    new KbVersionNotFoundException(
                            "no active version for tenant=" + tenantId + " kb=" + kbId));
        }
        if (!versionExists(tenantId, kbId, requested)) {
            throw new KbVersionNotFoundException(
                    "version " + requested + " not found for tenant=" + tenantId + " kb=" + kbId);
        }
        return requested;
    }

    public final void registerVersion(String tenantId, String kbId, long versionId,
                                      KbVersionMeta.Status initialStatus, String sourceLabel) {
        validateAll(tenantId, kbId, versionId);
        if (versionExists(tenantId, kbId, versionId)) {
            return;
        }
        String sql = "INSERT INTO kb_version "
                   + "(tenant_id, kb_id, version_id, status, created_at, published_at, doc_count, source_label) "
                   + "VALUES (?, ?, ?, ?, ?, NULL, 0, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setLong(3, versionId);
            ps.setString(4, initialStatus == null ? "DRAFT" : initialStatus.name());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            if (sourceLabel == null) ps.setNull(6, Types.VARCHAR);
            else ps.setString(6, sourceLabel);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("registerVersion failed", ex);
        }
    }

    final boolean versionExists(String tenantId, String kbId, long versionId) {
        String sql = "SELECT 1 FROM kb_version WHERE tenant_id = ? AND kb_id = ? AND version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            ps.setLong(3, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("versionExists failed", ex);
        }
    }

    private long getActiveVersionNoCheck(String tenantId, String kbId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version_id FROM kb_active_version WHERE tenant_id = ? AND kb_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("getActiveVersionNoCheck failed", ex);
        }
    }

    private KbVersionMeta readMeta(ResultSet rs) throws SQLException {
        long versionId = rs.getLong("version_id");
        String statusStr = rs.getString("status");
        KbVersionMeta.Status status;
        try {
            status = KbVersionMeta.Status.valueOf(statusStr);
        } catch (IllegalArgumentException ex) {
            status = KbVersionMeta.Status.ACTIVE;
        }
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp pubTs = rs.getTimestamp("published_at");
        Instant publishedAt = pubTs == null ? null : pubTs.toInstant();
        int docCount = rs.getInt("doc_count");
        String sourceLabel = rs.getString("source_label");
        return new KbVersionMeta(versionId, status, createdAt, publishedAt, docCount, sourceLabel);
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