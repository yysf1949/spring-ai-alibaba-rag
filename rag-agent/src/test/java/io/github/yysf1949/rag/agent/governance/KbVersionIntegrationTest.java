package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.builtin.DocumentVersionAction;
import io.github.yysf1949.rag.agent.builtin.DocumentVersionRequest;
import io.github.yysf1949.rag.agent.builtin.DocumentVersionResponse;
import io.github.yysf1949.rag.agent.builtin.DocumentVersionTool;
import io.github.yysf1949.rag.agent.builtin.KbVersionAction;
import io.github.yysf1949.rag.agent.builtin.KbVersionRequest;
import io.github.yysf1949.rag.agent.builtin.KbVersionResponse;
import io.github.yysf1949.rag.agent.builtin.KbVersionTool;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import io.github.yysf1949.rag.core.port.KbVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration verification test for knowledge-base version management.
 *
 * <p>Validates the article's claim: 「知识库内容更新后，引用同一知识的不同 Agent
 * 应该拿到一致的内容」. The test exercises the full KB version lifecycle through
 * the agent tool layer backed by in-memory service implementations, ensuring
 * that version create → publish → new-version → rollback → document-version
 * → multi-tenant isolation all behave correctly.</p>
 *
 * <p>Uses {@link InMemoryKbVersionService} and
 * {@link InMemoryDocumentVersionService} — no external DB required.</p>
 *
 * <p>Status mapping (requirement → actual enum):
 * <ul>
 *   <li>DRAFT → {@link KbVersionMeta.Status#DRAFT}</li>
 *   <li>PUBLISHED → {@link KbVersionMeta.Status#ACTIVE}</li>
 *   <li>ARCHIVED → {@link KbVersionMeta.Status#DEPRECATED}</li>
 * </ul>
 * </p>
 */
class KbVersionIntegrationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String KB_ID = "kb-main";
    private static final String DOC_ID = "doc-readme";

    private InMemoryKbVersionService kbVersionService;
    private InMemoryDocumentVersionService docVersionService;
    private KbVersionTool kbVersionTool;
    private DocumentVersionTool docVersionTool;

    @BeforeEach
    void setUp() {
        kbVersionService = new InMemoryKbVersionService();
        docVersionService = new InMemoryDocumentVersionService();
        kbVersionTool = new KbVersionTool(kbVersionService);
        docVersionTool = new DocumentVersionTool(docVersionService);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 1: 创建知识库版本 — register v1, verify DRAFT
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void createKbVersion_v1ShouldBeDraft() {
        // Act: register version 1
        kbVersionService.registerVersion(TENANT_A, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "initial-load");

        // Assert: version=1, status=DRAFT
        List<KbVersionMeta> versions = kbVersionService.listVersions(TENANT_A, KB_ID);
        assertEquals(1, versions.size(), "should have exactly 1 version");

        KbVersionMeta v1 = versions.get(0);
        assertEquals(1L, v1.versionId(), "version number should be 1");
        assertEquals(KbVersionMeta.Status.DRAFT, v1.status(), "newly created version should be DRAFT");
        assertEquals("initial-load", v1.sourceLabel());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 2: 发布版本 — publish v1, verify ACTIVE
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void publishKbVersion_v1ShouldBecomeActive() {
        // Arrange
        kbVersionService.registerVersion(TENANT_A, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "v1");

        // Act: publish v1 via tool
        KbVersionResponse resp = kbVersionTool.manage(
                new KbVersionRequest(KbVersionAction.SWITCH, TENANT_A, KB_ID, 1L));

        // Assert: tool response
        assertEquals(KbVersionAction.SWITCH, resp.action());
        assertEquals(1L, resp.activeVersionId());

        // Assert: service state — v1 is ACTIVE
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));
        KbVersionMeta v1 = findVersion(kbVersionService.listVersions(TENANT_A, KB_ID), 1);
        assertNotNull(v1, "v1 should exist");
        assertEquals(KbVersionMeta.Status.ACTIVE, v1.status(), "v1 should be ACTIVE after publish");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 3: 创建新版本 — v2 created as DRAFT, v1 remains ACTIVE
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void createKbVersion_v2DraftWhileV1StaysActive() {
        // Arrange: register & publish v1
        kbVersionService.registerVersion(TENANT_A, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "v1");
        kbVersionService.publish(TENANT_A, KB_ID, 1);

        // Act: register v2
        kbVersionService.registerVersion(TENANT_A, KB_ID, 2,
                KbVersionMeta.Status.DRAFT, "v2");

        // Assert: v2 = DRAFT, v1 = ACTIVE
        List<KbVersionMeta> versions = kbVersionService.listVersions(TENANT_A, KB_ID);
        assertEquals(2, versions.size(), "should have 2 versions");

        KbVersionMeta v2 = findVersion(versions, 2);
        KbVersionMeta v1 = findVersion(versions, 1);
        assertNotNull(v2, "v2 should exist");
        assertNotNull(v1, "v1 should still exist");

        assertEquals(2L, v2.versionId(), "v2 version number should be 2");
        assertEquals(KbVersionMeta.Status.DRAFT, v2.status(), "v2 should be DRAFT");
        assertEquals(KbVersionMeta.Status.ACTIVE, v1.status(), "v1 should still be ACTIVE");

        // Active pointer still on v1
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 4: 版本回滚 — rollback to v1, v2 becomes DEPRECATED
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void rollbackKbVersion_v2DeprecatedWhileV1StaysActive() {
        // Arrange: v1 (ACTIVE), v2 (ACTIVE after publish)
        kbVersionService.registerVersion(TENANT_A, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "v1");
        kbVersionService.publish(TENANT_A, KB_ID, 1);
        kbVersionService.registerVersion(TENANT_A, KB_ID, 2,
                KbVersionMeta.Status.DRAFT, "v2");
        kbVersionService.publish(TENANT_A, KB_ID, 2);
        assertEquals(2L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));

        // Act: rollback to v1 via tool
        KbVersionResponse resp = kbVersionTool.manage(
                new KbVersionRequest(KbVersionAction.ROLLBACK, TENANT_A, KB_ID, 1L));

        // Assert: tool response
        assertEquals(KbVersionAction.ROLLBACK, resp.action());
        assertEquals(1L, resp.activeVersionId());

        // Assert: v1 = ACTIVE, v2 = DEPRECATED
        KbVersionMeta v1 = findVersion(kbVersionService.listVersions(TENANT_A, KB_ID), 1);
        KbVersionMeta v2 = findVersion(kbVersionService.listVersions(TENANT_A, KB_ID), 2);
        assertNotNull(v1, "v1 should exist");
        assertNotNull(v2, "v2 should exist");

        assertEquals(KbVersionMeta.Status.ACTIVE, v1.status(),
                "v1 should still be ACTIVE after rollback");
        assertEquals(KbVersionMeta.Status.DEPRECATED, v2.status(),
                "v2 should be DEPRECATED (ARCHIVED) after rollback to v1");

        // Active pointer back on v1
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 5: 文档版本管理 — create & verify document versions
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void documentVersionManagement_createAndVerify() {
        // Register doc version 1
        DocumentVersionMeta docV1 = docVersionService.registerVersion(
                TENANT_A, KB_ID, DOC_ID, 1,
                DocumentVersionMeta.Status.DRAFT, "initial", 10);
        assertEquals(1L, docV1.versionId());
        assertEquals(DocumentVersionMeta.Status.DRAFT, docV1.status());
        assertEquals(10, docV1.chunkCount());

        // Publish doc v1 via tool
        DocumentVersionResponse publishResp = docVersionTool.manage(
                new DocumentVersionRequest(DocumentVersionAction.PUBLISH,
                        TENANT_A, KB_ID, DOC_ID, 1L, "release-1.0"));
        assertEquals(DocumentVersionAction.PUBLISH, publishResp.action());
        assertEquals(1L, publishResp.activeVersion());

        // Verify doc v1 is ACTIVE
        assertEquals(1L, docVersionService.getActiveVersion(TENANT_A, KB_ID, DOC_ID).orElse(-1L));
        List<DocumentVersionMeta> docVersions = docVersionService.listVersions(TENANT_A, KB_ID, DOC_ID);
        assertEquals(1, docVersions.size());
        assertEquals(DocumentVersionMeta.Status.ACTIVE, docVersions.get(0).status());

        // Register doc v2 and publish
        docVersionService.registerVersion(TENANT_A, KB_ID, DOC_ID, 2,
                DocumentVersionMeta.Status.DRAFT, "update", 15);
        docVersionTool.manage(
                new DocumentVersionRequest(DocumentVersionAction.PUBLISH,
                        TENANT_A, KB_ID, DOC_ID, 2L, "release-2.0"));
        assertEquals(2L, docVersionService.getActiveVersion(TENANT_A, KB_ID, DOC_ID).orElse(-1L));

        // Rollback doc to v1 via tool
        DocumentVersionResponse rollbackResp = docVersionTool.manage(
                new DocumentVersionRequest(DocumentVersionAction.ROLLBACK,
                        TENANT_A, KB_ID, DOC_ID, 1L, null));
        assertEquals(DocumentVersionAction.ROLLBACK, rollbackResp.action());
        assertEquals(1L, rollbackResp.activeVersion());

        // Verify: doc v1 = ACTIVE, doc v2 = DEPRECATED
        docVersions = docVersionService.listVersions(TENANT_A, KB_ID, DOC_ID);
        assertEquals(2, docVersions.size());
        DocumentVersionMeta dV1 = docVersions.stream()
                .filter(d -> d.versionId() == 1).findFirst().orElse(null);
        DocumentVersionMeta dV2 = docVersions.stream()
                .filter(d -> d.versionId() == 2).findFirst().orElse(null);
        assertNotNull(dV1, "doc v1 should exist");
        assertNotNull(dV2, "doc v2 should exist");
        assertEquals(DocumentVersionMeta.Status.ACTIVE, dV1.status(),
                "doc v1 should be ACTIVE after rollback");
        assertEquals(DocumentVersionMeta.Status.DEPRECATED, dV2.status(),
                "doc v2 should be DEPRECATED after rollback");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scenario 6: 多租户隔离 — different tenants have independent versions
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void multiTenantIsolation_versionsAreIndependent() {
        // Tenant A: register v1, publish
        kbVersionService.registerVersion(TENANT_A, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "tenant-a-v1");
        kbVersionService.publish(TENANT_A, KB_ID, 1);

        // Tenant B: register v1, publish
        kbVersionService.registerVersion(TENANT_B, KB_ID, 1,
                KbVersionMeta.Status.DRAFT, "tenant-b-v1");
        kbVersionService.publish(TENANT_B, KB_ID, 1);

        // Tenant A: register v2 (DRAFT only)
        kbVersionService.registerVersion(TENANT_A, KB_ID, 2,
                KbVersionMeta.Status.DRAFT, "tenant-a-v2");

        // Assert: Tenant A has 2 versions, Tenant B has 1
        List<KbVersionMeta> tenantAVersions = kbVersionService.listVersions(TENANT_A, KB_ID);
        List<KbVersionMeta> tenantBVersions = kbVersionService.listVersions(TENANT_B, KB_ID);
        assertEquals(2, tenantAVersions.size(), "Tenant A should have 2 versions");
        assertEquals(1, tenantBVersions.size(), "Tenant B should have 1 version");

        // Assert: Tenant A active = v1, Tenant B active = v1 (independent)
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_B, KB_ID).orElse(-1L));

        // Tenant B: publish v1 is idempotent — Tenant A unaffected
        kbVersionTool.manage(
                new KbVersionRequest(KbVersionAction.SWITCH, TENANT_B, KB_ID, 1L));
        assertEquals(2, kbVersionService.listVersions(TENANT_A, KB_ID).size(),
                "Tenant A version count should be unaffected by Tenant B operations");

        // Tenant A: publish v2 — Tenant B unaffected
        kbVersionService.publish(TENANT_A, KB_ID, 2);
        assertEquals(2L, kbVersionService.getActiveVersion(TENANT_A, KB_ID).orElse(-1L));
        assertEquals(1L, kbVersionService.getActiveVersion(TENANT_B, KB_ID).orElse(-1L),
                "Tenant B active version should be unaffected by Tenant A publish");

        // Verify Tenant A v1 is DEPRECATED, v2 is ACTIVE
        KbVersionMeta aV1 = findVersion(kbVersionService.listVersions(TENANT_A, KB_ID), 1);
        KbVersionMeta aV2 = findVersion(kbVersionService.listVersions(TENANT_A, KB_ID), 2);
        assertNotNull(aV1);
        assertNotNull(aV2);
        assertEquals(KbVersionMeta.Status.DEPRECATED, aV1.status(),
                "Tenant A v1 should be DEPRECATED after v2 publish");
        assertEquals(KbVersionMeta.Status.ACTIVE, aV2.status(),
                "Tenant A v2 should be ACTIVE");

        // Verify Tenant B v1 is still ACTIVE (isolation)
        KbVersionMeta bV1 = findVersion(kbVersionService.listVersions(TENANT_B, KB_ID), 1);
        assertNotNull(bV1);
        assertEquals(KbVersionMeta.Status.ACTIVE, bV1.status(),
                "Tenant B v1 should remain ACTIVE — isolation from Tenant A");

        // Multi-tenant tool-level isolation: list shows only Tenant B versions
        KbVersionResponse listB = kbVersionTool.manage(
                new KbVersionRequest(KbVersionAction.LIST, TENANT_B, KB_ID, null));
        assertEquals(1, listB.versions().size(),
                "Tenant B list should show only its own versions");
        assertEquals("tenant-b-v1", listB.versions().get(0).sourceLabel());

        KbVersionResponse listA = kbVersionTool.manage(
                new KbVersionRequest(KbVersionAction.LIST, TENANT_A, KB_ID, null));
        assertEquals(2, listA.versions().size(),
                "Tenant A list should show only its own versions");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static KbVersionMeta findVersion(List<KbVersionMeta> versions, long versionId) {
        return versions.stream()
                .filter(v -> v.versionId() == versionId)
                .findFirst()
                .orElse(null);
    }
}
