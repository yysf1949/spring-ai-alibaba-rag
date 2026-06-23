package io.github.yysf1949.rag.app.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Phase 34 (R5) — Lightweight principal carried in the SecurityContext
 * after a JWT is verified. We don't reuse Spring's {@code User} so a
 * downstream consumer can ask for {@link #tenantId()} and the OAuth2
 * scopes without re-parsing the token.
 */
public final class JwtPrincipal {

    private final String userId;
    private final String tenantId;
    private final Set<String> scopes;

    public JwtPrincipal(String userId, String tenantId, Set<String> scopes) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.scopes = scopes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
    }

    public String userId() { return userId; }
    public String tenantId() { return tenantId; }
    public Set<String> scopes() { return scopes; }

    @Override
    public String toString() {
        return "JwtPrincipal{userId=" + userId + ", tenantId=" + tenantId
                + ", scopes=" + scopes + "}";
    }
}
