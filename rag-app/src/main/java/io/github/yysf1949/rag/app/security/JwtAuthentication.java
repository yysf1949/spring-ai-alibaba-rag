package io.github.yysf1949.rag.app.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Phase 34 (R5) — Spring Security {@code Authentication} backed by a
 * verified JWT. Pre-authenticated (no credentials, no re-check on every
 * request — verification already happened in
 * {@link JwtTenantFilter}). Authorities are the {@code SCOPE_*} entries
 * the IdP attached to the token, e.g. {@code SCOPE_kb:read}.
 */
public class JwtAuthentication extends AbstractAuthenticationToken {

    private final JwtPrincipal principal;

    public JwtAuthentication(JwtPrincipal principal,
                             Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        // JWT has already been verified by JwtTenantFilter — this
        // Authentication is pre-authenticated regardless of whether
        // authorities are empty or not. We MUST call setAuthenticated(true)
        // explicitly: AbstractAuthenticationToken(List<GrantedAuthority>)
        // stores the authorities but does NOT set authenticated=true.
        // Without this, every request returns 403 even with valid ROLE_ADMIN.
        setAuthenticated(true);
    }

    @Override public Object getCredentials() { return ""; /* token already verified */ }
    @Override public Object getPrincipal() { return principal; }
    @Override public String getName() { return principal.userId(); }
}
