package io.github.yysf1949.rag.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.app.web.MockJwtController;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.redis.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the Phase 34 gateway layer. Three contract
 * pins are verified through the real Spring Security filter chain:
 *
 * <ol>
 *   <li><b>JWT-enabled + valid token</b>: {@code POST /api/ingest} with
 *       {@code Authorization: Bearer <jwt>} and a non-dev tenant reaches
 *       the controller (202 ACCEPTED) and the controller's tenant
 *       matches the JWT claim.</li>
 *   <li><b>JWT-enabled + no token</b>: the same request without the
 *       Authorization header is rejected at the filter (401, with the
 *       {@code UNAUTHORIZED} error code) and never reaches the
 *       controller.</li>
 *   <li><b>JWT-disabled + dev mode</b>: a request with just
 *       {@code X-Tenant-Id: dev} and no token still goes through, so
 *       the Phase 36 frontend keeps working. The filter writes a WARN
 *       log so the dev sees the prod path will reject it.</li>
 * </ol>
 *
 * <p>The test runs against the real Spring stack with the Redis-backed
 * {@link RateLimiter} {@code @MockBean}ed out (rate limiting is
 * covered by the {@code RateLimiterLiveIT} integration test in
 * {@code rag-redis}; mixing the two would force a docker dep into
 * every unit test run).</p>
 */
class JwtAuthEndToEndIT {

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.main.web-application-type=servlet",
            "spring.rag.redis.enabled=false",
            "spring.data.redis.host=nonexistent",
            "spring.data.redis.port=0",
            "spring.ai.openai.api-key=test-key",
            "rag.security.jwt.enabled=true",
            "rag.security.jwt.secret=jwt-e2e-test-secret-padded-to-32+bytes",
            "rag.security.jwt.dev-mode=false",
            "rag.security.api-key="
    })
    static class JwtEnabled {
        @Autowired private MockMvc mvc;
        @Autowired private ObjectMapper om;
        @MockBean private IngestService ingestService;
        @MockBean private AuditChannel auditChannel;
        @MockBean private RateLimiter rateLimiter;

        @org.junit.jupiter.api.BeforeEach
        void allowAll() {
            // Default: every check returns a permissive decision so the
            // filter lets the request through to the controller. Tests
            // that exercise 429 override this.
            org.mockito.Mockito.when(rateLimiter.check(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new RateLimiter.Decision(true, 0, 0));
            org.mockito.Mockito.when(rateLimiter.requestsPerWindow()).thenReturn(60);
            org.mockito.Mockito.when(rateLimiter.windowMs()).thenReturn(60_000L);
        }

        @Test
        void validJwt_acceptsRequest() throws Exception {
            String secret = "jwt-e2e-test-secret-padded-to-32+bytes";
            long exp = Instant.now().getEpochSecond() + 3600;
            String token = MockJwtController.TestHelper.build(
                    secret, "u-1", "tenant-A",
                    List.of("kb:read", "kb:write"), exp);

            String jobId = UUID.randomUUID().toString();
            when(ingestService.ingestAsync(any())).thenReturn(
                    new io.github.yysf1949.rag.core.model.IngestJob(
                            jobId, "tenant-A", "doc-1", "1",
                            io.github.yysf1949.rag.core.model.IngestJobStatus.PENDING,
                            0, 0, 0, 0, Instant.now(), Instant.now(), null));

            var body = Map.of(
                    "kbId", "kb-prod-001",
                    "documentId", "kb-prod-001/doc-1",
                    "documentVersion", 1,
                    "title", "t",
                    "sourceUri", "https://x",
                    "sections", List.of(Map.of("heading", "h", "content", "c")));

            mvc.perform(post("/api/ingest")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.tenantId").value("tenant-A"));
        }

        @Test
        void noJwt_returns401() throws Exception {
            var body = Map.of("kbId", "kb-1");
            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void badSignature_returns401() throws Exception {
            long exp = Instant.now().getEpochSecond() + 3600;
            String token = MockJwtController.TestHelper.build(
                    "WRONG-secret-padded-to-32+bytes-padding", "u-1", "tenant-A",
                    List.of("kb:read"), exp);
            var body = Map.of("kbId", "kb-1");
            mvc.perform(post("/api/ingest")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
        }
    }

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
            org.mockito.Mockito.when(rateLimiter.check(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new RateLimiter.Decision(true, 0, 0));
            org.mockito.Mockito.when(rateLimiter.requestsPerWindow()).thenReturn(60);
            org.mockito.Mockito.when(rateLimiter.windowMs()).thenReturn(60_000L);
        }

        @Test
        void devTenantHeaderWithoutJwt_stillAccepted() throws Exception {
            String jobId = UUID.randomUUID().toString();
            when(ingestService.ingestAsync(any())).thenReturn(
                    new io.github.yysf1949.rag.core.model.IngestJob(
                            jobId, "dev", "doc-1", "1",
                            io.github.yysf1949.rag.core.model.IngestJobStatus.PENDING,
                            0, 0, 0, 0, Instant.now(), Instant.now(), null));

            var body = Map.of(
                    "kbId", "kb-prod-001",
                    "documentId", "doc-1",
                    "documentVersion", 1,
                    "title", "t",
                    "sourceUri", "https://x",
                    "sections", List.of(Map.of("heading", "h", "content", "c")));

            mvc.perform(post("/api/ingest")
                            .header("X-Tenant-Id", "dev")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isAccepted());
        }
    }
}
