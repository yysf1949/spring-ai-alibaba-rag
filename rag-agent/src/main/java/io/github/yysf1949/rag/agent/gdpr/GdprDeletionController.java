package io.github.yysf1949.rag.agent.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 41 / R17 — GDPR 用户数据删除 REST API.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code DELETE /api/gdpr/users/{userId}} — 级联删除用户全部数据</li>
 * </ul>
 *
 * <h2>租户隔离</h2>
 * <p>tenantId 走 {@code X-Tenant-Id} header (跟 FeedbackController 风格一致).
 * 跨租户删除无效 (userId 只在 tenantId 命名空间内有意义)。</p>
 *
 * <h2>幂等性</h2>
 * <p>重复调用同一 (tenantId, userId) 安全 — 第二次返回 storeCounts 全 0。</p>
 *
 * <h2>审计</h2>
 * <p>每次调用自动记录 L4 风险等级审计事件 (通过 AuditLogger)。</p>
 */
@RestController
@RequestMapping("/api/gdpr")
@Tag(name = "GDPR", description = "Phase 41: GDPR 用户数据删除 (R17).")
public class GdprDeletionController {

    private static final Logger log = LoggerFactory.getLogger(GdprDeletionController.class);

    private final GdprDeletionPort deletionPort;

    public GdprDeletionController(GdprDeletionPort deletionPort) {
        this.deletionPort = deletionPort;
    }

    /**
     * 级联删除指定用户的全部数据 (GDPR Article 17 — Right to Erasure).
     *
     * @param tenantId 租户 ID (X-Tenant-Id header)
     * @param userId   用户 ID (path)
     * @return 删除结果 (含各存储层删除条数)
     */
    @DeleteMapping("/users/{userId}")
    @Operation(summary = "GDPR 用户数据删除",
               description = "级联删除指定用户在所有存储层的数据: 业务表 + 反馈 + 配额 + 发票 + 对话历史.")
    public ResponseEntity<GdprDeletionResult> deleteUser(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String userId) {

        log.info("GDPR deletion request: tenant={}, user={}", tenantId, userId);
        GdprDeletionResult result = deletionPort.deleteUser(tenantId, userId);

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.ok(result); // 200 with success=false for partial failures
        }
    }
}
