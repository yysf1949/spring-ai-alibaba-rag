package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-style tests for {@link H2DocumentVersionService} using H2
 * in-memory mode. Mirrors {@code H2KbVersionServiceTest} (Phase 18 P2) but
 * adds the {@code docId} dimension.
 */
class H2DocumentVersionServiceTest {

    private DataSource dataSource;
    private H2DocumentVersionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:docv-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.service = new H2DocumentVersionService(dataSource);
        this.service.ensureSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS DELETE FILES");
        }
    }

    @Test
    @DisplayName("register_then_listVersions_returns_single_draft")
    void register_then_listVersions_returns_single_draft() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, "v1-label", 5);

        List<DocumentVersionMeta> all = service.listVersions("t1", "kb1", "doc1");
        assertEquals(1, all.size());
        DocumentVersionMeta v = all.get(0);
        assertEquals(1L, v.versionId());
        assertEquals("doc1", v.docId());
        assertSame(DocumentVersionMeta.Status.DRAFT, v.status());
        assertEquals("v1-label", v.sourceLabel());
        assertEquals(5, v.chunkCount());
        assertNotNull(v.createdAt());
    }

    @Test
    @DisplayName("register_with_2_versions_listVersions_orders_newest_first")
    void register_with_2_versions_listVersions_orders_newest_first() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, "v1-label", 10);
        service.registerVersion("t1", "kb1", "doc1", 2L,
                DocumentVersionMeta.Status.DRAFT, "v2-label", 20);
        service.registerVersion("t1", "kb1", "doc1", 3L,
                DocumentVersionMeta.Status.DRAFT, "v3-label", 30);

        List<DocumentVersionMeta> all = service.listVersions("t1", "kb1", "doc1");
        assertEquals(3, all.size());
        // newest first
        assertEquals(3L, all.get(0).versionId());
        assertEquals(2L, all.get(1).versionId());
        assertEquals(1L, all.get(2).versionId());
        assertEquals("v3-label", all.get(0).sourceLabel());
        assertEquals("v1-label", all.get(2).sourceLabel());
    }

    @Test
    @DisplayName("publish_makes_version_active_and_marks_previous_deprecated")
    void publish_makes_version_active_and_marks_previous_deprecated() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.registerVersion("t1", "kb1", "doc1", 2L,
                DocumentVersionMeta.Status.DRAFT, null, 0);

        service.publish("t1", "kb1", "doc1", 1L, null);
        service.publish("t1", "kb1", "doc1", 2L, null);

        List<DocumentVersionMeta> all = service.listVersions("t1", "kb1", "doc1");
        DocumentVersionMeta v1 = all.stream()
                .filter(v -> v.versionId() == 1L).findFirst().orElseThrow();
        DocumentVersionMeta v2 = all.stream()
                .filter(v -> v.versionId() == 2L).findFirst().orElseThrow();
        assertSame(DocumentVersionMeta.Status.DEPRECATED, v1.status());
        assertSame(DocumentVersionMeta.Status.ACTIVE, v2.status());
        assertEquals(Optional.of(2L), service.getActiveVersion("t1", "kb1", "doc1"));
    }

    @Test
    @DisplayName("publish_same_version_twice_is_idempotent")
    void publish_same_version_twice_is_idempotent() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.publish("t1", "kb1", "doc1", 1L, null);

        DocumentVersionMeta first = service.listVersions("t1", "kb1", "doc1").get(0);
        java.time.Instant firstPublishedAt = first.publishedAt();
        assertSame(DocumentVersionMeta.Status.ACTIVE, first.status());
        assertNotNull(firstPublishedAt);

        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        // second publish of the same (already-active) version must be a no-op:
        // still ACTIVE, publishedAt unchanged.
        service.publish("t1", "kb1", "doc1", 1L, null);

        DocumentVersionMeta second = service.listVersions("t1", "kb1", "doc1").get(0);
        assertSame(DocumentVersionMeta.Status.ACTIVE, second.status());
        assertEquals(firstPublishedAt, second.publishedAt());
        assertEquals(Optional.of(1L), service.getActiveVersion("t1", "kb1", "doc1"));
    }

    @Test
    @DisplayName("publish_unknown_version_throws_DocumentVersionNotFoundException")
    void publish_unknown_version_throws_DocumentVersionNotFoundException() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        DocumentVersionNotFoundException ex = assertThrows(
                DocumentVersionNotFoundException.class,
                () -> service.publish("t1", "kb1", "doc1", 99L, null));
        assertEquals("t1", ex.tenantId());
        assertEquals("kb1", ex.kbId());
        assertEquals("doc1", ex.docId());
        assertEquals(99L, ex.versionId());
    }

    @Test
    @DisplayName("rollback_to_old_version_promotes_it_back_to_active")
    void rollback_to_old_version_promotes_it_back_to_active() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.registerVersion("t1", "kb1", "doc1", 2L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.publish("t1", "kb1", "doc1", 1L, null);
        service.publish("t1", "kb1", "doc1", 2L, null);

        service.rollback("t1", "kb1", "doc1", 1L);

        assertEquals(Optional.of(1L), service.getActiveVersion("t1", "kb1", "doc1"));
        DocumentVersionMeta v2 = service.listVersions("t1", "kb1", "doc1").stream()
                .filter(v -> v.versionId() == 2L).findFirst().orElseThrow();
        assertSame(DocumentVersionMeta.Status.DEPRECATED, v2.status());
        DocumentVersionMeta v1 = service.listVersions("t1", "kb1", "doc1").stream()
                .filter(v -> v.versionId() == 1L).findFirst().orElseThrow();
        assertSame(DocumentVersionMeta.Status.ACTIVE, v1.status());
    }

    @Test
    @DisplayName("getActiveVersion_empty_when_never_published")
    void getActiveVersion_empty_when_never_published() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        Optional<Long> active = service.getActiveVersion("t1", "kb1", "doc1");
        assertTrue(active.isEmpty());
    }

    @Test
    @DisplayName("getActiveVersion_returns_published_version_id")
    void getActiveVersion_returns_published_version_id() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.registerVersion("t1", "kb1", "doc1", 2L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.publish("t1", "kb1", "doc1", 2L, null);

        Optional<Long> active = service.getActiveVersion("t1", "kb1", "doc1");
        assertTrue(active.isPresent());
        assertEquals(2L, active.get());
    }

    @Test
    @DisplayName("resolveVersion_minus_one_resolves_to_active")
    void resolveVersion_minus_one_resolves_to_active() {
        service.registerVersion("t1", "kb1", "doc1", 5L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        service.publish("t1", "kb1", "doc1", 5L, null);

        assertEquals(5L, service.resolveVersion("t1", "kb1", "doc1", -1L));
        assertEquals(5L, service.resolveVersion("t1", "kb1", "doc1", -99L));
    }

    @Test
    @DisplayName("resolveVersion_minus_one_when_no_active_throws_NotFoundException")
    void resolveVersion_minus_one_when_no_active_throws_NotFoundException() {
        // Never published — no active version.
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 0);

        DocumentVersionNotFoundException ex = assertThrows(
                DocumentVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", "doc1", -1L));
        assertEquals("doc1", ex.docId());
        assertEquals(-1L, ex.versionId());
    }

    @Test
    @DisplayName("resolveVersion_positive_returns_same_when_exists")
    void resolveVersion_positive_returns_same_when_exists() {
        service.registerVersion("t1", "kb1", "doc1", 7L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        assertEquals(7L, service.resolveVersion("t1", "kb1", "doc1", 7L));
    }

    @Test
    @DisplayName("resolveVersion_positive_unknown_throws_NotFoundException")
    void resolveVersion_positive_unknown_throws_NotFoundException() {
        service.registerVersion("t1", "kb1", "doc1", 7L,
                DocumentVersionMeta.Status.DRAFT, null, 0);
        DocumentVersionNotFoundException ex = assertThrows(
                DocumentVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", "doc1", 99L));
        assertEquals(99L, ex.versionId());
        assertEquals("doc1", ex.docId());
    }

    @Test
    @DisplayName("registerVersion_idempotent_second_register_is_noop")
    void registerVersion_idempotent_second_register_is_noop() {
        // First registration wins; second registration with a different
        // status/label must NOT overwrite it.
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, "label", 5);
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.ACTIVE, "label-changed", 99);

        List<DocumentVersionMeta> all = service.listVersions("t1", "kb1", "doc1");
        assertEquals(1, all.size());
        DocumentVersionMeta v = all.get(0);
        assertEquals(1L, v.versionId());
        // First registration wins (idempotent — no overwrite).
        assertSame(DocumentVersionMeta.Status.DRAFT, v.status());
        assertEquals("label", v.sourceLabel());
        assertEquals(5, v.chunkCount());
    }

    @Test
    @DisplayName("publish_sets_publishedAt_and_sourceLabel")
    void publish_sets_publishedAt_and_sourceLabel() {
        service.registerVersion("t1", "kb1", "doc1", 1L,
                DocumentVersionMeta.Status.DRAFT, null, 7);

        // Before publish — publishedAt should be null, label null.
        DocumentVersionMeta before = service.listVersions("t1", "kb1", "doc1").get(0);
        assertEquals(null, before.publishedAt());
        assertEquals(null, before.sourceLabel());

        DocumentVersionMeta after = service.publish("t1", "kb1", "doc1", 1L, "Q2-drop");

        assertSame(DocumentVersionMeta.Status.ACTIVE, after.status());
        assertNotNull(after.publishedAt());
        assertEquals("Q2-drop", after.sourceLabel());
        assertEquals(7, after.chunkCount());
        assertEquals(1L, after.versionId());
        assertEquals("doc1", after.docId());

        // Reload from listVersions to confirm persistence.
        DocumentVersionMeta persisted = service.listVersions("t1", "kb1", "doc1").stream()
                .filter(v -> v.versionId() == 1L).findFirst().orElseThrow();
        assertSame(DocumentVersionMeta.Status.ACTIVE, persisted.status());
        assertNotNull(persisted.publishedAt());
        assertEquals("Q2-drop", persisted.sourceLabel());
    }
}