package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.gdpr.GdprDeletionResult;
import io.github.yysf1949.rag.agent.gdpr.GdprDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GDPR "Right to be Forgotten" REST API — Phase 41 T1 (R17).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code DELETE /api/gdpr/users/{userId}} — 删除指定用户全部个人数据</li>
 *   <li>{@code DELETE /api/gdpr/tenants/{tenantId}} — 删除指定租户全部数据 (admin)</li>
 * </ul>
 *
 * <h2>认证</h2>
 * <p>需要 ROLE_ADMIN (复用 Phase 34 SecurityConfig). 租户隔离通过 JWT claim.</p>
 */
@RestController
@RequestMapping("/api/gdpr")
public class GdprController {

    private static final Logger log = LoggerFactory.getLogger(GdprController.class);

    private final GdprDeletionService gdprService;

    public GdprController(GdprDeletionService gdprService) {
        this.gdprService = gdprService;
    }

    /**
     * 删除指定用户的全部个人数据 (GDPR Article 17).
     *
     * @param tenantId 租户 ID (从 JWT claim 或 X-Tenant-Id header)
     * @param userId   被删除用户 ID
     * @return 删除结果统计
     */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<GdprDeletionResult> deleteUserData(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String userId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("GDPR delete user: tenantId={}, userId={}", tenantId, userId);
        GdprDeletionResult result = gdprService.deleteUserData(tenantId, userId);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定租户的全部数据 (admin 级全量删除).
     *
     * @param tenantId 被删除租户 ID
     * @return 删除结果统计
     */
    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<GdprDeletionResult> deleteTenantData(
            @PathVariable String tenantId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("GDPR delete tenant: tenantId={}", tenantId);
        GdprDeletionResult result = gdprService.deleteTenantData(tenantId);
        return ResponseEntity.ok(result);
    }
}
