package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.model.AuditEvent;
import io.github.yysf1949.rag.redis.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for Phase 34-T34b — the admin-only
 * {@link MockAdminController} gated by {@code ROLE_ADMIN}.
 *
 * <p>Four contract pins are verified through the real Spring Security
 * filter chain (URL-level + method-level defence in depth):</p>
 * <ol>
 *   <li><b>JWT-enabled + admin token</b>: {@code POST /admin/tenants/...}
 *       with a JWT carrying {@code "scope": "admin"} reaches the
 *       controller (200 OK) and the audit channel sees exactly one
 *       TENANT_CONFIG_CHANGE event.</li>
 *   <li><b>JWT-enabled + normal token</b>: same request with a JWT
 *       carrying only {@code kb:read kb:write} is rejected (403) and
 *       the audit channel sees zero events.</li>
 *   <li><b>JWT-disabled + dev mode</b>: a {@code X-Tenant-Id: dev}
 *       request (dev-mode anonymous) hits the URL-level gate and is
 *       rejected (403) — dev mode does NOT grant ROLE_ADMIN.</li>
 *   <li><b>JWT-enabled + admin token + GET</b>: {@code GET /admin/tenants}
 *       is reachable (200 OK) and returns the in-memory store
 *       including the tenant just POSTed.</li>
 * </ol>
 *
 * <p>The Redis-backed {@link RateLimiter} is {@code @MockBean}ed out
 * (rate-limit logic is covered by {@code RateLimiterLiveIT} in
 * rag-redis). The {@link AuditChannel} is mocked so we can assert the
 * exact event payload without going through SLF4J — this is the same
 * pattern used by {@code JwtAuthEndToEndIT}.</p>
 */
class MockAdminControllerTest {

    private static final String JWT_SECRET = "jwt-secret-padded-to-32+bytes-padding";

    // ──────────────────────────────────────────────────────────────────────
    // 1. JWT-enabled + admin token → 200 + audit event
    // ──────────────────────────────────────────────────────────────────────

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.main.web-application-type=servlet",
            "spring.rag.redis.enabled=false",
            "spring.data.redis.host=nonexistent",
            "spring.data.redis.port=0",
            "spring.ai.openai.api-key=test-key",
            "rag.security.jwt.enabled=true",
            "rag.security.jwt.secret=" + JWT_SECRET,
            "rag.security.jwt.dev-mode=false",
            "rag.security.api-key="
    })
    static class JwtAdmin {
        @Autowired private MockMvc mvc;
        @Autowired private ObjectMapper om;
        @MockBean private IngestService ingestService;
        @MockBean private AuditChannel auditChannel;
        @MockBean private RateLimiter rateLimiter;

        @org.junit.jupiter.api.BeforeEach
        void allowAll() {
            when(rateLimiter.check(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new RateLimiter.Decision(true, 0, 0));
            when(rateLimiter.requestsPerWindow()).thenReturn(60);
            when(rateLimiter.windowMs()).thenReturn(60_000L);
        }

        @Test
        void adminJwt_canUpdateTenantConfig() throws Exception {
            String token = MockJwtController.TestHelper.build(
                    JWT_SECRET, "admin-1", "tenant-A",
                    List.of("admin"), Instant.now().getEpochSecond() + 3600);

            var body = Map.of("config", "{\"kbWhitelist\":[\"kb-1\"]}");

            mvc.perform(post("/admin/tenants/tenant-A/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenant").value("tenant-A"))
                    .andExpect(jsonPath("$.actor").value("admin-1"))
                    .andExpect(jsonPath("$.newConfig").value("{\"kbWhitelist\":[\"kb-1\"]}"));

            // Verify the audit channel was called with a TENANT_CONFIG_CHANGE event
            // carrying the new config + actor (admin-1).
            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditChannel, times(1)).record(captor.capture());
            AuditEvent ev = captor.getValue();
            assertEquals(AuditEvent.Type.TENANT_CONFIG_CHANGE, ev.type(),
                    "admin POST must emit TENANT_CONFIG_CHANGE audit event");
            assertEquals("tenant-A", ev.tenantId());
            assertEquals("admin-1", ev.actorId(),
                    "actorId must be the JWT subject");
            assertEquals("SUCCESS", ev.outcome());
            assertEquals("{\"kbWhitelist\":[\"kb-1\"]}", ev.fields().get("newConfig"));
            // previousConfig is null on first write — key must be absent, not present-null
            assertNull(ev.fields().get("oldConfig"),
                    "oldConfig must be absent on first write (not present-and-null)");
        }

        @Test
        void adminJwt_canUpdateTenantConfig_secondCall_carriesOldConfig() throws Exception {
            String token = MockJwtController.TestHelper.build(
                    JWT_SECRET, "admin-2", "tenant-B",
                    List.of("admin"), Instant.now().getEpochSecond() + 3600);

            var firstBody  = Map.of("config", "{\"v\":1}");
            var secondBody = Map.of("config", "{\"v\":2}");

            // First POST — establishes the old config.
            mvc.perform(post("/admin/tenants/tenant-B/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(firstBody)))
                    .andExpect(status().isOk());

            // Second POST — must carry the previous config in the audit event.
            mvc.perform(post("/admin/tenants/tenant-B/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(secondBody)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.previousConfig").value("{\"v\":1}"))
                    .andExpect(jsonPath("$.newConfig").value("{\"v\":2}"));

            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditChannel, times(2)).record(captor.capture());
            List<AuditEvent> events = captor.getAllValues();
            AuditEvent secondEvent = events.get(1);
            assertEquals("{\"v\":1}", secondEvent.fields().get("oldConfig"),
                    "second call must carry the previous config in oldConfig field");
            assertEquals("{\"v\":2}", secondEvent.fields().get("newConfig"));
        }

        @Test
        void adminJwt_canListTenants() throws Exception {
            String token = MockJwtController.TestHelper.build(
                    JWT_SECRET, "admin-3", "tenant-X",
                    List.of("admin"), Instant.now().getEpochSecond() + 3600);

            // Seed a tenant via POST so the GET has something to show.
            mvc.perform(post("/admin/tenants/tenant-X/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("config", "{\"seed\":1}"))))
                    .andExpect(status().isOk());

            mvc.perform(get("/admin/tenants")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.tenants[0].tenant").value("tenant-X"))
                    .andExpect(jsonPath("$.tenants[0].config").value("{\"seed\":1}"));

            // GET does NOT emit audit events (read-only).
            verify(auditChannel, times(1)).record(any());
        }

        @Test
        void normalJwt_returns403() throws Exception {
            // Same shape as admin token but scope does NOT include "admin" —
            // the JwtTenantFilter must NOT grant ROLE_ADMIN.
            String token = MockJwtController.TestHelper.build(
                    JWT_SECRET, "u-regular", "tenant-A",
                    List.of("kb:read", "kb:write"),
                    Instant.now().getEpochSecond() + 3600);

            var body = Map.of("config", "{\"x\":1}");

            mvc.perform(post("/admin/tenants/tenant-A/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isForbidden());

            // No audit event on denied request — the change did not happen.
            verify(auditChannel, never()).record(any());
        }

        @Test
        void adminJwt_missingConfigBody_returns400() throws Exception {
            String token = MockJwtController.TestHelper.build(
                    JWT_SECRET, "admin-4", "tenant-A",
                    List.of("admin"), Instant.now().getEpochSecond() + 3600);

            mvc.perform(post("/admin/tenants/tenant-A/config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(auditChannel, never()).record(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. Dev mode (no JWT) → /admin/** returns 403 (anonymous ≠ admin)
    // ──────────────────────────────────────────────────────────────────────

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.main.web-application-type=servlet",
            "spring.rag.redis.enabled=false",
            "spring.data.redis.host=nonexistent",
            "spring.data.redis.port=0",
            "spring.ai.openai.api-key=test-key",
            "rag.security.jwt.enabled=false",
            "rag.security.jwt.dev-mode=true",
            "rag.security.api-key="
    })
    static class DevMode {
        @Autowired private MockMvc mvc;
        @Autowired private ObjectMapper om;
        @MockBean private IngestService ingestService;
        @MockBean private AuditChannel auditChannel;
        @MockBean private RateLimiter rateLimiter;

        @org.junit.jupiter.api.BeforeEach
        void allowAll() {
            when(rateLimiter.check(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new RateLimiter.Decision(true, 0, 0));
            when(rateLimiter.requestsPerWindow()).thenReturn(60);
            when(rateLimiter.windowMs()).thenReturn(60_000L);
        }

        @Test
        void devAnonymous_returns403() throws Exception {
            // Dev mode accepts X-Tenant-Id without JWT for the normal
            // /api/** endpoints, but /admin/** must STILL require
            // ROLE_ADMIN — and dev anonymous does not have it.
            var body = Map.of("config", "{\"x\":1}");

            mvc.perform(post("/admin/tenants/dev/config")
                            .header("X-Tenant-Id", "dev")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isForbidden());

            verify(auditChannel, never()).record(any());
        }
    }
}
