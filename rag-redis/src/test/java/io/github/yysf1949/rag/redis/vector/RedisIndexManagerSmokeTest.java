package io.github.yysf1949.rag.redis.vector;

import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.config.RedisProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test against the local {@code rag-redis-stack} podman container.
 *
 * <p>Activated with {@code -Dredis.smoke.test=true}. Skipped by default so the
 * build doesn't require a live Redis. This is the integration verification for
 * Phase 4-P1.</p>
 */
@EnabledIfSystemProperty(named = "redis.smoke.test", matches = "true")
class RedisIndexManagerSmokeTest {

    private static RedisConnection connection;
    private static RedisIndexManager manager;

    @BeforeAll
    static void connect() {
        // The container 'rag-redis-stack' is started manually by the developer.
        RedisProperties props = new RedisProperties(
                "127.0.0.1", 6379, null, 0,
                4, 2, 1, 2000, 5000);
        connection = new RedisConnection(props);
        connection.init();
        manager = new RedisIndexManager(connection);
    }

    @AfterAll
    static void teardown() {
        if (connection != null) connection.shutdown();
    }

    @Test
    void create_then_drop_index_roundTrip() {
        String tenant = "smokeTenant";
        long version = 9999L;

        manager.dropIndex(tenant, version, false, true);
        manager.ensureIndex(tenant, version, false);

        // ensureIndex again should be idempotent (info returns OK without recreating)
        manager.ensureIndex(tenant, version, false);

        List_indexes_must_include_tenant_index();

        manager.dropIndex(tenant, version, false, true);
        assertFalse(manager.listIndexes(tenant).contains(manager.indexName(tenant, version)));
    }

    private void List_indexes_must_include_tenant_index() {
        // smoke test sub-assertion
        assertTrue(manager.listIndexes("smokeTenant").stream()
                .anyMatch(n -> n.contains("smokeTenant")));
    }

    @Test
    void publishPointer_roundTrip() {
        manager.setPublishPointer("smokeTenant", "kb1", 7L);
        assertEquals(7L, manager.getPublishPointer("smokeTenant", "kb1"));
        // overwrite
        manager.setPublishPointer("smokeTenant", "kb1", 8L);
        assertEquals(8L, manager.getPublishPointer("smokeTenant", "kb1"));
    }
}