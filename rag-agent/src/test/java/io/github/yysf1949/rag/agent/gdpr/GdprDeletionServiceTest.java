package io.github.yysf1949.rag.agent.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GDPR 删除服务测试 — Phase 41 T1 (R17).
 *
 * <p>纯单元测试, 不依赖 Spring context. 用 H2 内存数据库 + schema-h2.sql 自动建表.</p>
 */
class GdprDeletionServiceTest {

    private GdprDeletionService gdprService;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema-h2.sql")
                .build();
        this.jdbc = new JdbcTemplate(ds);

        insertTestData();

        this.gdprService = new DefaultGdprDeletionService(
                ds, null, null, null, null, null, false
        );
    }

    private void insertTestData() {
        // chat_memory 表不在 schema-h2.sql, 需手动建
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_memory (" +
                "conversation_id VARCHAR(128) NOT NULL, " +
                "seq INT NOT NULL, " +
                "message_type VARCHAR(32) NOT NULL, " +
                "content TEXT NOT NULL, " +
                "metadata_json TEXT, " +
                "tenant_id VARCHAR(64), " +
                "user_id VARCHAR(64), " +
                "created_at BIGINT, " +
                "PRIMARY KEY (conversation_id, seq))");

        // agent_after_service_audit 表不在 schema-h2.sql, 需手动建
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_after_service_audit (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "tenant_id VARCHAR(64) NOT NULL, " +
                "user_id VARCHAR(64), " +
                "action VARCHAR(64), " +
                "resource_type VARCHAR(64), " +
                "resource_id VARCHAR(128), " +
                "outcome VARCHAR(32), " +
                "created_at BIGINT)");

        // 清理残留数据 (BeforeEach 每个测试都跑)
        jdbc.execute("DELETE FROM agent_feedback");
        jdbc.execute("DELETE FROM chat_memory");
        jdbc.execute("DELETE FROM agent_after_service_audit");
        jdbc.execute("DELETE FROM agent_tenant_quota");
        jdbc.execute("DELETE FROM agent_usage_counter");
        jdbc.execute("DELETE FROM agent_invoice");

        // feedback — user-1 in tenant-A: 2 records
        jdbc.update("INSERT INTO agent_feedback (feedback_id, tenant_id, user_id, conversation_id, " +
                "thumb, rating, comment, source_channel, kb_version, created_at) " +
                "VALUES ('FB-001', 'tenant-A', 'user-1', 'conv-1', 'UP', 5, 'great', 'web', 'v1', 1000)");
        jdbc.update("INSERT INTO agent_feedback (feedback_id, tenant_id, user_id, conversation_id, " +
                "thumb, rating, comment, source_channel, kb_version, created_at) " +
                "VALUES ('FB-002', 'tenant-A', 'user-1', 'conv-2', 'DOWN', 2, 'bad', 'web', 'v1', 2000)");
        // feedback — user-2 in tenant-A: 1 record
        jdbc.update("INSERT INTO agent_feedback (feedback_id, tenant_id, user_id, conversation_id, " +
                "thumb, rating, comment, source_channel, kb_version, created_at) " +
                "VALUES ('FB-003', 'tenant-A', 'user-2', 'conv-3', 'UP', 4, 'ok', 'api', 'v1', 3000)");
        // feedback — tenant-B: 1 record (isolation test)
        jdbc.update("INSERT INTO agent_feedback (feedback_id, tenant_id, user_id, conversation_id, " +
                "thumb, rating, comment, source_channel, kb_version, created_at) " +
                "VALUES ('FB-004', 'tenant-B', 'user-3', 'conv-4', 'UP', 5, 'great', 'web', 'v1', 4000)");

        // chat_memory — conv-1: 2 messages, conv-2: 1 message, conv-3: 1 message
        jdbc.update("INSERT INTO chat_memory (conversation_id, seq, message_type, content, metadata_json, " +
                "tenant_id, user_id, created_at) " +
                "VALUES ('conv-1', 0, 'USER', 'hello', '{}', 'tenant-A', 'user-1', 1000)");
        jdbc.update("INSERT INTO chat_memory (conversation_id, seq, message_type, content, metadata_json, " +
                "tenant_id, user_id, created_at) " +
                "VALUES ('conv-1', 1, 'ASSISTANT', 'hi there', '{}', 'tenant-A', 'user-1', 1001)");
        jdbc.update("INSERT INTO chat_memory (conversation_id, seq, message_type, content, metadata_json, " +
                "tenant_id, user_id, created_at) " +
                "VALUES ('conv-2', 0, 'USER', 'question', '{}', 'tenant-A', 'user-1', 2000)");
        jdbc.update("INSERT INTO chat_memory (conversation_id, seq, message_type, content, metadata_json, " +
                "tenant_id, user_id, created_at) " +
                "VALUES ('conv-3', 0, 'USER', 'help', '{}', 'tenant-A', 'user-2', 3000)");

        // audit
        jdbc.update("INSERT INTO agent_after_service_audit (tenant_id, user_id, action, " +
                "resource_type, resource_id, outcome, created_at) " +
                "VALUES ('tenant-A', 'user-1', 'REFUND', 'ORDER', 'ORD-1', 'SUCCESS', 1000)");
        jdbc.update("INSERT INTO agent_after_service_audit (tenant_id, user_id, action, " +
                "resource_type, resource_id, outcome, created_at) " +
                "VALUES ('tenant-A', 'user-2', 'COMPLAINT', 'TICKET', 'TK-1', 'SUCCESS', 2000)");

        // quota
        jdbc.update("INSERT INTO agent_tenant_quota (tenant_id, tier, monthly_call_limit, " +
                "monthly_token_limit, effective_from) " +
                "VALUES ('tenant-A', 'PRO', 10000, 1000000, 1000)");
        jdbc.update("INSERT INTO agent_usage_counter (tenant_id, month_key, resource, counter_value) " +
                "VALUES ('tenant-A', '2026-06', 'calls', 500)");

        // invoice
        jdbc.update("INSERT INTO agent_invoice (invoice_id, tenant_id, amount_cents, currency, " +
                "status, payment_method, created_at) " +
                "VALUES ('INV-001', 'tenant-A', 9900, 'CNY', 'PAID', 'STRIPE', 1000)");
    }

    @Test
    @DisplayName("deleteUserData: 删除指定用户的 feedback + memory + audit")
    void deleteUserData_cascadesCorrectly() {
        GdprDeletionResult result = gdprService.deleteUserData("tenant-A", "user-1");

        assertEquals("tenant-A", result.tenantId());
        assertEquals("user-1", result.userId());
        assertEquals(2, result.feedbackDeleted(), "应删除 2 条 feedback (FB-001, FB-002)");
        assertTrue(result.memoryDeleted() >= 3, "应删除 chat_memory (conv-1: 2条 + conv-2: 1条)");
        assertEquals(1, result.auditLogsDeleted(), "应删除 1 条 audit log");
        assertFalse(result.deleteTenant());
        assertTrue(result.durationMs() >= 0);

        // 验证 user-2 的数据不受影响
        Integer remainingFeedback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_feedback WHERE tenant_id = 'tenant-A' AND user_id = 'user-2'",
                Integer.class);
        assertEquals(1, remainingFeedback, "user-2 的 feedback 应保留");

        Integer remainingMemory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_memory WHERE conversation_id = 'conv-3'",
                Integer.class);
        assertEquals(1, remainingMemory, "user-2 的 memory 应保留");
    }

    @Test
    @DisplayName("deleteUserData: 幂等 — 重复调用返回 0 deleted")
    void deleteUserData_isIdempotent() {
        gdprService.deleteUserData("tenant-A", "user-1");
        GdprDeletionResult result2 = gdprService.deleteUserData("tenant-A", "user-1");

        assertEquals(0, result2.feedbackDeleted(), "第二次调用应删除 0 条 feedback");
        assertEquals(0, result2.auditLogsDeleted(), "第二次调用应删除 0 条 audit");
    }

    @Test
    @DisplayName("deleteUserData: 跨租户隔离 — 不删其他租户数据")
    void deleteUserData_tenantIsolation() {
        gdprService.deleteUserData("tenant-A", "user-1");

        Integer tenantBFeedback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_feedback WHERE tenant_id = 'tenant-B'",
                Integer.class);
        assertEquals(1, tenantBFeedback, "tenant-B 的 feedback 不应被删");
    }

    @Test
    @DisplayName("deleteTenantData: 删除租户全部数据包括 quota + invoice")
    void deleteTenantData_cascadesAll() {
        GdprDeletionResult result = gdprService.deleteTenantData("tenant-A");

        assertTrue(result.deleteTenant(), "deleteTenant 应为 true");
        assertEquals(3, result.feedbackDeleted(), "应删除 tenant-A 全部 3 条 feedback");
        assertTrue(result.quotaDeleted() >= 2, "应删除 quota + usage_counter (2 行)");
        assertEquals(1, result.invoicesDeleted(), "应删除 1 条 invoice");

        // 验证 tenant-B 不受影响
        Integer tenantBFeedback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_feedback WHERE tenant_id = 'tenant-B'",
                Integer.class);
        assertEquals(1, tenantBFeedback, "tenant-B 的 feedback 不应被删");
    }

    @Test
    @DisplayName("deleteTenantData: 幂等")
    void deleteTenantData_isIdempotent() {
        gdprService.deleteTenantData("tenant-A");
        GdprDeletionResult result2 = gdprService.deleteTenantData("tenant-A");

        assertEquals(0, result2.feedbackDeleted());
        assertEquals(0, result2.invoicesDeleted());
    }
}
