package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.app.security.JwtTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 34 (R5) — Mock JWT issuer for development and test.
 *
 * <p>Issues HS256-signed JWTs without consulting an external IdP. The
 * controller is a Phase 34 affordance: a real deployment will point
 * {@code rag.security.jwt.enabled} at the production IdP (e.g. Keycloak
 * via JWKS) and shut this endpoint off. Until then, hitting
 * {@code POST /api/auth/mock-token} with a JSON body returns a token
 * that {@link io.github.yysf1949.rag.app.security.JwtTenantFilter} will
 * accept.</p>
 *
 * <p><b>Disabled in dev mode</b> (the default): when
 * {@code rag.security.jwt.enabled=false} the controller responds with
 * {@code 404 NOT_FOUND} so a misconfigured prod environment does not
 * silently leak tokens. The endpoint is reachable only when JWT auth
 * is actually in use.</p>
 *
 * <h2>Request</h2>
 * <pre>
 * POST /api/auth/mock-token
 * {
 *   "userId":  "u-1234",
 *   "tenant":  "acme",
 *   "scopes":  ["kb:read", "kb:write"]
 * }
 * </pre>
 *
 * <h2>Response</h2>
 * <pre>
 * 200 OK
 * {
 *   "token":      "eyJ...",
 *   "expiresIn":  3600,
 *   "tokenType":  "Bearer"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/auth")
public class MockJwtController {

    private static final Logger log = LoggerFactory.getLogger(MockJwtController.class);

    private final boolean enabled;
    private final byte[] secret;
    private final long expirationSeconds;

    public MockJwtController(
            @Value("${rag.security.jwt.enabled:false}") boolean enabled,
            @Value("${rag.security.jwt.secret:}") String secret,
            @Value("${rag.security.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.enabled = enabled;
        this.expirationSeconds = expirationSeconds;
        if (enabled) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "rag.security.jwt.enabled=true but rag.security.jwt.secret is blank — "
                                + "refusing to start the mock JWT issuer without a secret");
            }
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        } else {
            this.secret = new byte[0];
        }
    }

    /**
     * Issue a JWT. Returns 404 when JWT auth is disabled so the endpoint
     * is invisible in dev mode.
     */
    @PostMapping("/mock-token")
    public ResponseEntity<?> issue(@RequestBody(required = false) MockTokenRequest req) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOT_FOUND",
                            "message", "Mock JWT issuer is disabled (rag.security.jwt.enabled=false)"));
        }
        if (req == null || req.userId == null || req.userId.isBlank()
                || req.tenant == null || req.tenant.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "userId and tenant are required"));
        }
        long now = Instant.now().getEpochSecond();
        long exp = now + expirationSeconds;
        String scope = (req.scopes == null || req.scopes.isEmpty())
                ? "kb:read kb:write agent:invoke"
                : String.join(" ", req.scopes);

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{"
                + "\"sub\":\"" + escape(req.userId) + "\","
                + "\"tenantId\":\"" + escape(req.tenant) + "\","
                + "\"scope\":\"" + escape(scope) + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + exp
                + "}";
        try {
            String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
            String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            String signature = base64Url(hmacSha256(signingInput));
            String token = signingInput + "." + signature;

            log.info("🎫 issued mock JWT for userId={} tenant={} scopes={} ttl={}s",
                    req.userId, req.tenant, scope, expirationSeconds);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", token);
            body.put("tokenType", "Bearer");
            body.put("expiresIn", expirationSeconds);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("failed to sign mock JWT", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR",
                            "message", "failed to sign JWT: " + e.getMessage()));
        }
    }

    private byte[] hmacSha256(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Request body for {@link #issue(MockTokenRequest)}. */
    public static class MockTokenRequest {
        public String userId;
        public String tenant;
        public java.util.List<String> scopes;
    }

    /**
     * Test helper — re-export so unit tests can share the canonical
     * header / payload construction without duplicating the JSON.
     */
    public static final class TestHelper {
        /** Build the same compact JWT the controller would issue. */
        public static String build(String secret, String userId, String tenant,
                                   java.util.List<String> scopes, long expEpoch) {
            String scope = (scopes == null || scopes.isEmpty())
                    ? "kb:read kb:write agent:invoke" : String.join(" ", scopes);
            String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String payloadJson = "{"
                    + "\"sub\":\"" + userId + "\","
                    + "\"tenantId\":\"" + tenant + "\","
                    + "\"scope\":\"" + scope + "\","
                    + "\"exp\":" + expEpoch
                    + "}";
            try {
                String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
                String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
                String signingInput = header + "." + payload;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
                return signingInput + "." + signature;
            } catch (Exception e) {
                throw new IllegalStateException("test JWT build failed", e);
            }
        }

        /**
         * Build a JWT directly from a claim payload string (no JSON
         * construction in the test). Useful for negative-path tests
         * that need to omit the {@code tenantId} claim.
         */
        public static String buildRaw(String secret, String headerJson, String payloadJson) {
            try {
                String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
                String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
                String signingInput = header + "." + payload;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
                return signingInput + "." + signature;
            } catch (Exception e) {
                throw new IllegalStateException("test JWT build failed", e);
            }
        }

        private TestHelper() {}
    }
}
