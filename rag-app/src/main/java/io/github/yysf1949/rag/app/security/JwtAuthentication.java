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
        // setAuthenticated(true) is called by super when authorities are
        // non-empty; for empty authorities we set it explicitly below.
        if (authorities == null || authorities.isEmpty()) {
            setAuthenticated(true);
        }
    }

    @Override public Object getCredentials() { return ""; /* token already verified */ }
    @Override public Object getPrincipal() { return principal; }
    @Override public String getName() { return principal.userId(); }
}
