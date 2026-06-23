package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.app.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 34-T34b — Mock admin controller for tenant configuration.
 *
 * <p>Provides the admin-only endpoints that exercise the
 * {@code ROLE_ADMIN} gate installed by T34a + this task's SecurityConfig
 * upgrade. Implementation is in-memory only — this is a Phase 34
 * affordance so the audit-channel TENANT_CONFIG_CHANGE event flow has
 * a real caller to exercise end-to-end. A production deployment will
 * swap the in-memory map for a persistent store.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /admin/tenants/{tenant}/config} — replace a tenant's
 *       config. Requires {@code ROLE_ADMIN}. Emits a
 *       {@link io.github.yysf1949.rag.core.model.AuditEvent.Type#TENANT_CONFIG_CHANGE}
 *       event with the old/new config + actor.</li>
 *   <li>{@code GET /admin/tenants} — list all known tenants + their
 *       current config. Requires {@code ROLE_ADMIN}.</li>
 * </ul>
 *
 * <h2>Authorization model</h2>
 * <p>The {@code @PreAuthorize("hasRole('ADMIN')")} annotation is the
 * primary gate; {@code SecurityConfig.filterChain} also has a URL-level
 * {@code /admin/**} matcher as defence-in-depth (belt + suspenders).
 * The JWT-to-role mapping lives in
 * {@link io.github.yysf1949.rag.app.security.JwtTenantFilter}: a token
 * carrying the {@code admin} scope is granted {@code ROLE_ADMIN}.</p>
 *
 * <h2>Audit semantics</h2>
 * <p>Every successful {@code POST} emits exactly one
 * TENANT_CONFIG_CHANGE event with actor = JWT subject (or "anonymous"
 * if dev mode somehow reached here — should never happen because the
 * URL gate blocks anon, but the code is defensive). Failure paths
 * (validation) do NOT emit — the change did not happen.</p>
 */
@RestController
@RequestMapping("/admin")
public class MockAdminController {

    private static final Logger log = LoggerFactory.getLogger(MockAdminController.class);

    private final AuditChannel auditChannel;
    /** In-memory tenant config store; thread-safe via ConcurrentHashMap. */
    private final ConcurrentMap<String, String> tenantConfigs = new ConcurrentHashMap<>();

    public MockAdminController(AuditChannel auditChannel) {
        this.auditChannel = Objects.requireNonNull(auditChannel, "auditChannel");
    }

    /**
     * Replace the named tenant's config. The previous config (if any) is
     * captured in the audit event so compliance tooling can show the diff.
     */
    @PostMapping("/tenants/{tenant}/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateTenantConfig(@PathVariable("tenant") String tenant,
                                                 @RequestBody(required = false) TenantConfigRequest req) {
        if (tenant == null || tenant.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "tenant path variable is required"));
        }
        if (req == null || req.config == null || req.config.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "config body field is required"));
        }

        String oldConfig = tenantConfigs.put(tenant, req.config);
        String actor = currentActor();
        String requestId = currentRequestId();

        auditChannel.recordTenantConfigChange(tenant, oldConfig, req.config, actor, requestId);

        log.info("🔧 tenant '{}' config updated by '{}' ({}→{} chars)",
                tenant, actor,
                oldConfig == null ? 0 : oldConfig.length(),
                req.config.length());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant);
        body.put("previousConfig", oldConfig);
        body.put("newConfig", req.config);
        body.put("actor", actor);
        body.put("outcome", "SUCCESS");
        return ResponseEntity.ok(body);
    }

    /**
     * List all known tenants and their current config snapshot.
     * Read-only — does NOT emit an audit event (read actions are covered
     * by the request log + access log; TENANT_CONFIG_CHANGE is reserved
     * for write actions per the audit taxonomy).
     */
    @GetMapping("/tenants")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listTenants() {
        List<Map<String, Object>> entries = tenantConfigs.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenant", e.getKey());
                    m.put("config", e.getValue());
                    return m;
                })
                .sorted((a, b) -> ((String) a.get("tenant")).compareTo((String) b.get("tenant")))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenants", entries);
        body.put("count", entries.size());
        return ResponseEntity.ok(body);
    }

    /**
     * Best-effort extraction of the actor user id from the JWT principal
     * installed by JwtTenantFilter. Returns "anonymous" if no auth is
     * present (shouldn't happen because @PreAuthorize gates this code).
     */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "anonymous";
        Object principal = auth.getPrincipal();
        if (principal instanceof JwtPrincipal jp) {
            return jp.userId();
        }
        return auth.getName() == null ? "anonymous" : auth.getName();
    }

    /**
     * Best-effort extraction of the request correlation id from MDC.
     * Returns null if no MDC key is set (audit event then omits the
     * field per AuditChannel contract).
     */
    private static String currentRequestId() {
        return org.slf4j.MDC.get("requestId");
    }

    /** Request body for {@link #updateTenantConfig}. */
    public static class TenantConfigRequest {
        /** The new tenant config payload (JSON string, free-form). */
        public String config;
    }
}
