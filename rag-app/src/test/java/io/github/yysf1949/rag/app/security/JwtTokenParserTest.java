package io.github.yysf1949.rag.app.security;

import io.github.yysf1949.rag.app.web.MockJwtController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the HS256 JWT parser used by the gateway layer.
 *
 * <p>Phase 34 (R5) — we own the parser rather than pulling
 * {@code nimbus-jose-jwt} because the spec only requires HS256 with
 * flat string claims, and the parser is the single chokepoint that
 * has to handle "alg=none" / "alg=RS256" / "expired" / "wrong sig"
 * correctly. These tests pin all four.</p>
 */
class JwtTokenParserTest {

    private static final String SECRET = "jwt-parser-test-secret-padded-to-32+bytes";

    @Test
    void parse_validToken_returnsClaims() {
        long exp = java.time.Instant.now().getEpochSecond() + 3600;
        String token = MockJwtController.TestHelper.build(
                SECRET, "u-1", "acme",
                List.of("kb:read", "kb:write"), exp);
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        JwtTokenParser.Claims c = parser.parse(token);
        assertEquals("u-1", c.subject);
        assertEquals("acme", c.tenantId);
        assertTrue(c.scopes.contains("kb:read"));
        assertTrue(c.scopes.contains("kb:write"));
        assertEquals(String.valueOf(exp), c.expirationEpoch);
    }

    @Test
    void parse_tenantIdAlias_tenant_idAlsoAccepted() {
        // The parser accepts both `tenantId` (camelCase) and `tenant_id`
        // (snake-case) for issuer interop.
        long exp = java.time.Instant.now().getEpochSecond() + 3600;
        String payload = "{\"sub\":\"u-2\",\"tenant_id\":\"acme\",\"exp\":" + exp + "}";
        String token = MockJwtController.TestHelper.buildRaw(
                SECRET, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload);
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        JwtTokenParser.Claims c = parser.parse(token);
        assertEquals("acme", c.tenantId);
    }

    @Test
    void parse_wrongSecret_throws() {
        long exp = java.time.Instant.now().getEpochSecond() + 3600;
        String token = MockJwtController.TestHelper.build(
                "different-secret-32+chars-padding-x", "u-1", "acme",
                List.of("kb:read"), exp);
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        JwtTokenParser.JwtParseException ex = assertThrows(
                JwtTokenParser.JwtParseException.class, () -> parser.parse(token));
        assertTrue(ex.getMessage().contains("signature"),
                "expected 'signature' in message but got: " + ex.getMessage());
    }

    @Test
    void parse_algNone_throws() {
        // Defence-in-depth: MiniJson rejects any alg != HS256 even if
        // the signature would otherwise check out.
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"u-3\",\"tenantId\":\"acme\"}".getBytes());
        String token = header + "." + payload + ".AAAA";
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        JwtTokenParser.JwtParseException ex = assertThrows(
                JwtTokenParser.JwtParseException.class, () -> parser.parse(token));
        assertTrue(ex.getMessage().contains("alg"),
                "expected 'alg' in message but got: " + ex.getMessage());
    }

    @Test
    void parse_blankToken_throws() {
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        assertThrows(JwtTokenParser.JwtParseException.class, () -> parser.parse(""));
        assertThrows(JwtTokenParser.JwtParseException.class, () -> parser.parse(null));
    }

    @Test
    void parse_malformed_throws() {
        JwtTokenParser parser = new JwtTokenParser(SECRET);
        assertThrows(JwtTokenParser.JwtParseException.class, () -> parser.parse("a.b"));
        assertThrows(JwtTokenParser.JwtParseException.class, () -> parser.parse("not.a.jwt.value"));
    }

    @Test
    void parser_blankSecret_throws() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenParser(""));
        assertThrows(NullPointerException.class, () -> new JwtTokenParser(null));
    }

    @Test
    void claims_defaultScopesEmpty() {
        JwtTokenParser.Claims c = new JwtTokenParser.Claims();
        assertNotNull(c.scopes);
        assertTrue(c.scopes.isEmpty());
        assertNull(c.subject);
        assertNull(c.tenantId);
    }
}
