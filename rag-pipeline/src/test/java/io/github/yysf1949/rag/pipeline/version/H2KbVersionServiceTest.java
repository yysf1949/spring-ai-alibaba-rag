package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-style tests for {@link H2KbVersionService} using H2 in-memory mode.
 */
class H2KbVersionServiceTest {

    private DataSource dataSource;
    private H2KbVersionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:kbv-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.service = new H2KbVersionService(dataSource);
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
    void ensureSchemaIsIdempotent() {
        service.ensureSchema();
        service.ensureSchema();
    }

    @Test
    void registerThenListVersionsNewestFirst() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.DRAFT, "v1-label");
        service.registerVersion("t1", "kb1", 2, KbVersionMeta.Status.STAGING, "v2-label");
        service.registerVersion("t1", "kb1", 3, KbVersionMeta.Status.STAGING, "v3-label");

        List<KbVersionMeta> all = service.listVersions("t1", "kb1");
        assertEquals(3, all.size());
        assertEquals(3L, all.get(0).versionId());  // newest first
        assertSame(KbVersionMeta.Status.STAGING, all.get(0).status());
        assertEquals(1L, all.get(2).versionId());
    }

    @Test
    void registerVersionIsIdempotent() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.DRAFT, "label");
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, "label-changed");

        List<KbVersionMeta> all = service.listVersions("t1", "kb1");
        assertEquals(1, all.size());
        assertEquals(1L, all.get(0).versionId());
        // First registration wins (idempotent — no overwrite)
        assertSame(KbVersionMeta.Status.DRAFT, all.get(0).status());
        assertEquals("label", all.get(0).sourceLabel());
    }

    @Test
    void publishThenGetActiveReturnsIt() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        service.registerVersion("t1", "kb1", 2, KbVersionMeta.Status.STAGING, null);

        service.publish("t1", "kb1", 1L);

        Optional<Long> active = service.getActiveVersion("t1", "kb1");
        assertTrue(active.isPresent());
        assertEquals(1L, active.get());

        KbVersionMeta v1 = service.listVersions("t1", "kb1").stream()
                .filter(v -> v.versionId() == 1L).findFirst().orElseThrow();
        assertSame(KbVersionMeta.Status.ACTIVE, v1.status());
        assertNotNull(v1.publishedAt());
    }

    @Test
    void publishDeprecatesPreviouslyActive() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        service.registerVersion("t1", "kb1", 2, KbVersionMeta.Status.STAGING, null);
        service.publish("t1", "kb1", 1L);
        service.publish("t1", "kb1", 2L);

        List<KbVersionMeta> all = service.listVersions("t1", "kb1");
        KbVersionMeta v1 = all.stream().filter(v -> v.versionId() == 1L).findFirst().orElseThrow();
        KbVersionMeta v2 = all.stream().filter(v -> v.versionId() == 2L).findFirst().orElseThrow();
        assertSame(KbVersionMeta.Status.DEPRECATED, v1.status());
        assertSame(KbVersionMeta.Status.ACTIVE, v2.status());
        assertEquals(Optional.of(2L), service.getActiveVersion("t1", "kb1"));
    }

    @Test
    void publishIsIdempotentWhenAlreadyActive() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        service.publish("t1", "kb1", 1L);
        KbVersionMeta firstActive = service.listVersions("t1", "kb1").get(0);
        java.time.Instant firstPublishedAt = firstActive.publishedAt();

        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        service.publish("t1", "kb1", 1L);  // should be a no-op
        KbVersionMeta secondActive = service.listVersions("t1", "kb1").get(0);
        assertSame(KbVersionMeta.Status.ACTIVE, secondActive.status());
        assertEquals(firstPublishedAt, secondActive.publishedAt());
    }

    @Test
    void publishUnknownVersionThrows() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        KbVersionNotFoundException ex = assertThrows(
                KbVersionNotFoundException.class,
                () -> service.publish("t1", "kb1", 99L));
        assertTrue(ex.getMessage().contains("kb_version row missing"));
    }

    @Test
    void rollbackReactivatesPreviousVersion() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        service.registerVersion("t1", "kb1", 2, KbVersionMeta.Status.STAGING, null);
        service.publish("t1", "kb1", 1L);
        service.publish("t1", "kb1", 2L);
        service.rollback("t1", "kb1", 1L);

        assertEquals(Optional.of(1L), service.getActiveVersion("t1", "kb1"));
        KbVersionMeta v2 = service.listVersions("t1", "kb1").stream()
                .filter(v -> v.versionId() == 2L).findFirst().orElseThrow();
        assertSame(KbVersionMeta.Status.DEPRECATED, v2.status());
    }

    @Test
    void resolveVersionNegativeReturnsActive() {
        service.registerVersion("t1", "kb1", 5, KbVersionMeta.Status.STAGING, null);
        service.publish("t1", "kb1", 5L);

        assertEquals(5L, service.resolveVersion("t1", "kb1", -1L));
        assertEquals(5L, service.resolveVersion("t1", "kb1", -99L));
    }

    @Test
    void resolveVersionNegativeWithoutActiveThrows() {
        KbVersionNotFoundException ex = assertThrows(
                KbVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", -1L));
        assertTrue(ex.getMessage().contains("no active version"));
    }

    @Test
    void resolveVersionSpecificReturnsItIfExists() {
        service.registerVersion("t1", "kb1", 7, KbVersionMeta.Status.STAGING, null);
        assertEquals(7L, service.resolveVersion("t1", "kb1", 7L));
    }

    @Test
    void resolveVersionSpecificThrowsIfMissing() {
        service.registerVersion("t1", "kb1", 7, KbVersionMeta.Status.STAGING, null);
        KbVersionNotFoundException ex = assertThrows(
                KbVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", 99L));
        assertTrue(ex.getMessage().contains("version 99 not found"));
    }

    @Test
    void multiTenantIsolation() {
        service.registerVersion("t1", "kb1", 1, KbVersionMeta.Status.STAGING, null);
        service.registerVersion("t2", "kb1", 99, KbVersionMeta.Status.STAGING, null);

        service.publish("t1", "kb1", 1L);
        assertEquals(Optional.of(1L), service.getActiveVersion("t1", "kb1"));
        assertTrue(service.getActiveVersion("t2", "kb1").isEmpty());

        List<KbVersionMeta> t2List = service.listVersions("t2", "kb1");
        assertEquals(1, t2List.size());
        assertEquals(99L, t2List.get(0).versionId());
    }

    @Test
    void blankTenantIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listVersions("", "kb1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.listVersions("t1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("t1", "kb1", -1L));
    }
}