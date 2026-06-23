package io.github.yysf1949.rag.app.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        Map<String, String> claims = MiniJson.parseObject(
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

    /** Thrown on any parse/verification failure — the filter maps this to 401. */
    public static final class JwtParseException extends RuntimeException {
        public JwtParseException(String message) { super(message); }
        public JwtParseException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Minimal JSON object parser — just enough to read string-valued
     * fields from a flat JWT payload. Avoids pulling a JSON dep for one
     * caller. NOT a general-purpose parser; no nested objects, no arrays.
     */
    static final class MiniJson {
        static Map<String, String> parseObject(String s) {
            if (s == null) throw new JwtParseException("JSON is null");
            int i = skipWs(s, 0);
            if (i >= s.length() || s.charAt(i) != '{') {
                throw new JwtParseException("expected '{', got '" + safe(s, i) + "'");
            }
            i = skipWs(s, i + 1);
            Map<String, String> out = new LinkedHashMap<>();
            if (i < s.length() && s.charAt(i) == '}') return out;
            while (i < s.length()) {
                i = skipWs(s, i);
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new JwtParseException("expected string key at " + i);
                }
                int keyStart = ++i;
                int keyEnd = findStringEnd(s, keyStart);
                String key = unescape(s.substring(keyStart, keyEnd));
                i = skipWs(s, keyEnd + 1);
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new JwtParseException("expected ':' at " + i);
                }
                i = skipWs(s, i + 1);
                if (i >= s.length() || s.charAt(i) != '"') {
                    // we only support string values; skip non-strings
                    // (numbers, booleans) so a JWT with `exp: 12345`
                    // doesn't fail to parse.
                    int end = skipValue(s, i);
                    if (end < 0) throw new JwtParseException("unterminated value at " + i);
                    i = skipWs(s, end);
                } else {
                    int valStart = ++i;
                    int valEnd = findStringEnd(s, valStart);
                    out.put(key, unescape(s.substring(valStart, valEnd)));
                    i = skipWs(s, valEnd + 1);
                }
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    return out;
                }
                throw new JwtParseException("expected ',' or '}' at " + i);
            }
            throw new JwtParseException("unterminated JSON object");
        }

        private static int skipWs(String s, int i) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            return i;
        }

        private static int findStringEnd(String s, int start) {
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == '"') return i;
            }
            throw new JwtParseException("unterminated string starting at " + start);
        }

        private static int skipValue(String s, int i) {
            int depth = 0;
            boolean inStr = false;
            for (; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inStr) {
                    if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                    if (c == '"') inStr = false;
                    continue;
                }
                if (c == '"') { inStr = true; continue; }
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') depth--;
                if (depth == 0 && (c == ',' || c == '}' || c == ']')) return i;
            }
            return -1;
        }

        private static String unescape(String s) {
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    switch (n) {
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'u' -> {
                            if (i + 4 < s.length()) {
                                out.append((char) Integer.parseInt(
                                        s.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                        }
                        default -> out.append(n);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static String safe(String s, int i) {
            if (i >= s.length()) return "<eof>";
            return String.valueOf(s.charAt(i));
        }
    }
}
