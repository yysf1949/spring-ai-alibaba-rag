package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MockJwtController} — specifically the JWT
 * signing shape and the disabled-mode 404 behaviour. End-to-end
 * coverage (MockMvc + filter chain) lives in
 * {@link io.github.yysf1949.rag.app.security.JwtAuthEndToEndIT}.
 */
class MockJwtControllerTest {

    @Test
    void issue_404WhenJwtDisabled() {
        MockJwtController c = new MockJwtController(false, "", 3600L);
        var resp = c.issue(new MockJwtController.MockTokenRequest() {{
            userId = "u-1"; tenant = "acme";
        }});
        assertEquals(404, resp.getStatusCodeValue());
    }

    @Test
    void issue_400WhenMissingFields() {
        MockJwtController c = new MockJwtController(true,
                "jwt-secret-padded-to-32+bytes-padding", 3600L);
        var resp = c.issue(null);
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void issue_400WhenUserIdBlank() {
        MockJwtController c = new MockJwtController(true,
                "jwt-secret-padded-to-32+bytes-padding", 3600L);
        var resp = c.issue(new MockJwtController.MockTokenRequest() {{
            userId = ""; tenant = "acme";
        }});
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void issue_200WithValidToken() throws Exception {
        String secret = "jwt-secret-padded-to-32+bytes-padding";
        MockJwtController c = new MockJwtController(true, secret, 3600L);
        var resp = c.issue(new MockJwtController.MockTokenRequest() {{
            userId = "u-1"; tenant = "acme";
            scopes = List.of("kb:read", "kb:write");
        }});
        assertEquals(200, resp.getStatusCodeValue());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) resp.getBody();
        assertNotNull(body);
        String token = (String) body.get("token");
        assertNotNull(token);
        assertEquals(3600L, body.get("expiresIn"));
        assertEquals("Bearer", body.get("tokenType"));

        // Decode the payload and verify the claims.
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        JsonNode payload = new ObjectMapper().readTree(payloadJson);
        assertEquals("u-1", payload.get("sub").asText());
        assertEquals("acme", payload.get("tenantId").asText());
        assertEquals("kb:read kb:write", payload.get("scope").asText());
        assertTrue(payload.has("exp"));
        assertTrue(payload.has("iat"));
    }

    @Test
    void constructor_500IfEnabledButBlankSecret() {
        // We want a loud failure at startup, not silent fallback to
        // an empty-key JWT.
        try {
            new MockJwtController(true, "", 3600L);
            assertTrue(false, "expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("secret"));
        }
    }

    @Test
    void testHelper_buildProduces3PartToken() {
        String secret = "any-secret-padded-to-32-bytes-padding-padding";
        long exp = java.time.Instant.now().getEpochSecond() + 3600;
        String token = MockJwtController.TestHelper.build(
                secret, "u-1", "acme",
                List.of("kb:read"), exp);
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);
    }
}
