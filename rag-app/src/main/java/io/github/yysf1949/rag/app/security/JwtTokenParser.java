package io.github.yysf1949.rag.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HS256 JWT parser + verifier — design spec R5 (JWT/OAuth2 gateway).
 *
 * <p>Phase 34 narrow-scope rebuild: the spec calls for HS256 + JWKS, but a
 * production JWKS rollout needs a real {@code nimbus-jose-jwt} dependency,
 * a JWKS endpoint, and a public-key cache. We are building the <b>gateway
 * layer</b> in a way that lets us swap HS256 → RS256/JWKS later by
 * replacing just this class — the filter, scopes, and rate limiter don't
 * care how the signature was verified, only that
 * {@link Claims} is populated.</p>
 *
 * <p>Token shape (RFC 7519):</p>
 * <pre>
 *   header.payload.signature
 *   where header  = base64url(JSON { "alg":"HS256", "typ":"JWT" })
 *         payload = base64url(JSON claims)
 *         signature = base64url(HMAC-SHA256(secret, header + "." + payload))
 * </pre>
 *
 * <p>We intentionally parse the JSON claims with a hand-rolled mini-parser
 * (no {@code jakarta.json} or {@code Gson} — rag-app already pulls Jackson
 * transitively through spring-boot-starter-web, so a tiny parser keeps
 * the new code free of a new top-level dependency). The claims we
 * actually look at are flat strings; nested structures can wait for the
 * real JWKS upgrade.</p>
 */
public final class JwtTokenParser {

    private final byte[] secret;

    public JwtTokenParser(String secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("HS256 secret must not be blank");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verify and parse a compact-serialised JWT.
     *
     * @param token "header.payload.signature"
     * @return parsed claims
     * @throws JwtParseException if the token is malformed, the signature
     *     does not verify, or the algorithm is not HS256.
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtParseException("token is blank");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtParseException("expected 3 segments, got " + parts.length);
        }
        byte[] headerJson = decodeUrl(parts[0], "header");
        byte[] payloadJson = decodeUrl(parts[1], "payload");
        byte[] signature = decodeUrl(parts[2], "signature");

        // 1. Verify alg = HS256 (defence-in-depth — never trust the header
        // alone, but a "none" or "RS256" token with the right secret still
        // goes through the constant-time HMAC check below and we want a
        // fast fail).
        String headerStr = new String(headerJson, StandardCharsets.UTF_8);
        if (!headerStr.contains("\"alg\":\"HS256\"")) {
            throw new JwtParseException("unsupported alg (expected HS256): " + headerStr);
        }

        // 2. HMAC-SHA256(secret, header + "." + payload) == signature.
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!java.security.MessageDigest.isEqual(expected, signature)) {
                throw new JwtParseException("signature mismatch");
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new JwtParseException("HMAC error: " + e.getMessage(), e);
        }

        // 3. Parse claims (we only need tenantId, sub, scope, exp).
        Map<String, String> claims = readStringClaims(
                new String(payloadJson, StandardCharsets.UTF_8));
        Claims c = new Claims();
        c.tenantId = claims.get("tenantId");
        if (c.tenantId == null) {
            // Fall back to standard claim set if the issuer doesn't use
            // a dedicated tenantId field — many real-world issuers embed
            // the tenant in `sub` or in a custom claim; we accept the
            // most common one explicitly.
            String tenant = claims.get("tenant_id");
            if (tenant != null) {
                c.tenantId = tenant;
            }
        }
        c.subject = claims.get("sub");
        String scope = claims.get("scope");
        if (scope != null) {
            for (String s : scope.split("\\s+")) {
                if (!s.isEmpty()) c.scopes.add(s);
            }
        }
        c.expirationEpoch = claims.get("exp");
        return c;
    }

    private static byte[] decodeUrl(String s, String name) {
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new JwtParseException("invalid base64url in " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parsed JWT claims. {@code scopes} starts empty and is mutated by
     * {@link #parse(String)}; never null.
     */
    public static final class Claims {
        public String tenantId;
        public String subject;
        public String expirationEpoch;
        public final java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
    }

    /**
     * Read a flat JWT claim object using Jackson. Only string-valued
     * fields (and the standard numeric {@code exp} / {@code iat} claims)
     * are surfaced — arrays and nested objects are skipped, since the
     * HS256 gateway layer does not need them. The parser is a thin
     * Jackson wrapper: it doesn't pull in a new dependency because
     * rag-app already includes Jackson through
     * spring-boot-starter-web.
     */
    private static Map<String, String> readStringClaims(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (root == null || !root.isObject()) {
                throw new JwtParseException("expected JSON object");
            }
            Map<String, String> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) continue;
                if (v.isTextual()) {
                    out.put(e.getKey(), v.asText());
                } else if (v.isNumber()) {
                    // Keep exp / iat as text so Claims.expirationEpoch
                    // stays a String (callers can Long.parseLong it).
                    out.put(e.getKey(), v.asText());
                }
                // arrays / objects are silently skipped — not used.
            }
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new JwtParseException("malformed JSON: " + e.getOriginalMessage(), e);
        } catch (java.io.IOException e) {
            throw new JwtParseException("JSON read failed: " + e.getMessage(), e);
        }
    }

    /** Thrown on any parse/verification failure — the filter maps this to 401. */
    public static final class JwtParseException extends RuntimeException {
        public JwtParseException(String message) { super(message); }
        public JwtParseException(String message, Throwable cause) { super(message, cause); }
    }
}
