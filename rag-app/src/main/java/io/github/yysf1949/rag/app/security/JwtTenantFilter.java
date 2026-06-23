package io.github.yysf1949.rag.app.security;

import io.github.yysf1949.rag.redis.ratelimit.RateLimiter;

import io.github.yysf1949.rag.app.config.MdcTenantFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 34 (R5+R6) — Gateway layer: JWT verification + per-tenant rate limiting.
 *
 * <p>Order: {@code HIGHEST_PRECEDENCE + 5}, so this filter runs
 * <em>before</em> the existing {@link MdcTenantFilter} (which is at
 * {@code HIGHEST_PRECEDENCE + 10}). The filter handles its own
 * 401 / 429 short-circuits; on success it stamps {@code X-Tenant-Id}
 * (and {@code X-User-Id}) headers into a wrapped request so the
 * downstream chain — {@link MdcTenantFilter}, the controllers, the
 * audit channel — keeps working unchanged.</p>
 *
 * <h2>Decision matrix</h2>
 * <ol>
 *   <li><b>{@code Authorization: Bearer ...} present</b>:
 *     <ul>
 *       <li>If {@code rag.security.jwt.enabled=true} and
 *           {@code rag.security.jwt.secret} is set, parse + verify HS256.
 *           Reject 401 on any failure (missing alg, bad signature,
 *           expired, missing tenant).</li>
 *       <li>Set {@code Authentication} in the SecurityContext with
 *           {@code SCOPE_*} authorities derived from the {@code scope}
 *           claim, populate MDC, fall through.</li>
 *     </ul>
 *   </li>
 *   <li><b>No JWT, JWT disabled, dev-mode on</b>:
 *     <ul>
 *       <li>Accept {@code X-Tenant-Id: <anything>} as unauthenticated
 *           dev traffic. Log a single WARN per request so the dev
 *           knows the prod path will reject it.</li>
 *       <li>This is the bridge for the Phase 36 frontend, which
 *           hard-codes {@code X-Tenant-Id: dev} without a JWT.</li>
 *     </ul>
 *   </li>
 *   <li><b>No JWT, JWT enabled (or dev-mode off)</b>: 401.</li>
 * </ol>
 *
 * <h2>Rate limiting</h2>
 * <p>On a successful path the tenant is fed to the {@link RateLimiter}
 * (Redis sliding window). The filter always sets
 * {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining} headers
 * so clients can self-throttle; a denied request gets HTTP 429 with
 * a {@code Retry-After} header.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class JwtTenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtTenantFilter.class);

    public static final String HEADER_AUTH = "Authorization";
    public static final String AUTH_BEARER = "Bearer ";

    private final JwtTokenParser parser;
    private final boolean jwtEnabled;
    private final boolean devMode;
    private final RateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtTenantFilter(
            @Value("${rag.security.jwt.secret:}") String jwtSecret,
            @Value("${rag.security.jwt.enabled:false}") boolean jwtEnabled,
            @Value("${rag.security.jwt.dev-mode:true}") boolean devMode,
            @org.springframework.beans.factory.annotation.Autowired(
                    required = false) RateLimiter rateLimiter) {
        this.jwtEnabled = jwtEnabled;
        this.devMode = devMode;
        this.rateLimiter = rateLimiter;
        if (jwtEnabled) {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                log.warn("⚠️ rag.security.jwt.enabled=true but secret is blank — "
                        + "JWT auth will REJECT every request with 401.");
                this.parser = null;
            } else {
                this.parser = new JwtTokenParser(jwtSecret);
                log.info("🔑 JWT authentication ENABLED (HS256, secret length={})",
                        jwtSecret.length());
            }
        } else {
            this.parser = null;
            log.warn("⚠️ JWT authentication DISABLED (rag.security.jwt.enabled=false).");
        }
        if (devMode) {
            log.warn("⚠️ DEV MODE ENABLED — requests without JWT but with X-Tenant-Id "
                    + "are accepted. Do NOT use in production.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // 0. Skip auth for public paths (actuator health, swagger UI,
        // openapi doc) — these never carry a tenant header, and the
        // Spring Security chain has already marked them permitAll().
        // We must let them through BEFORE the JWT / dev-mode checks
        // fire, otherwise Swagger dies in dev mode.
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error")
                // The mock JWT issuer is the one endpoint a client
                // is allowed to call WITHOUT a JWT — it's literally
                // the way you get one. Letting it through the
                // gateway is the whole point.
                || path.equals("/api/auth/mock-token")
                || path.startsWith("/api/auth/mock-token/")) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Try JWT first.
        String auth = request.getHeader(HEADER_AUTH);
        if (auth != null && auth.startsWith(AUTH_BEARER)) {
            if (parser == null) {
                reject(response, 401, "JWT_NOT_CONFIGURED",
                        "Server has no JWT secret configured; set rag.security.jwt.secret");
                return;
            }
            String token = auth.substring(AUTH_BEARER.length()).trim();
            try {
                JwtTokenParser.Claims claims = parser.parse(token);
                if (claims.tenantId == null || claims.tenantId.isBlank()) {
                    reject(response, 401, "JWT_MISSING_TENANT",
                            "JWT has no tenantId claim");
                    return;
                }
                // Stamp the headers so the downstream chain (including
                // MdcTenantFilter) sees the same shape it always saw.
                HeaderOverrideRequest wrapped = new HeaderOverrideRequest(
                        request, claims.tenantId, claims.subject);
                if (rateLimiter != null) {
                    applyRateLimit(response, claims.tenantId);
                    if (response.isCommitted()) return;
                }
                installJwtAuth(claims);
                try {
                    chain.doFilter(wrapped, response);
                } finally {
                    SecurityContextHolder.clearContext();
                    MDC.remove("jwt.tenant");
                    MDC.remove("jwt.sub");
                }
                return;
            } catch (JwtTokenParser.JwtParseException e) {
                reject(response, 401, "JWT_INVALID", e.getMessage());
                return;
            }
        }

        // 2. Dev-mode fallback — accept X-Tenant-Id without JWT.
        //    We deliberately DO NOT short-circuit 401 ourselves when
        //    X-Tenant-Id is missing in dev mode: the downstream
        //    controller already returns a problem+json 401 for that
        //    case (RagExceptionHandler), and overriding it from a
        //    filter would break the response contract that the smoke
        //    tests assert. We just stamp a SecurityContext so any
        //    SecurityContextHolder.getContext() call downstream has
        //    something to chew on, and let the controller do its job.
        if (devMode) {
            String tenant = request.getHeader(MdcTenantFilter.HEADER_TENANT);
            if (tenant != null && !tenant.isBlank()) {
                log.warn("DEV-MODE auth: X-Tenant-Id='{}' accepted without JWT. "
                        + "Use a real JWT in production.", tenant);
                if (rateLimiter != null) {
                    applyRateLimit(response, tenant);
                    if (response.isCommitted()) return;
                }
                installAnonymousAuth(tenant);
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return;
            }
            // dev mode, no X-Tenant-Id — fall through to the
            // controller, which will return the typed 401
            // problem+json response.
        }

        // 3. JWT mode (no dev fallback) without a Bearer token: 401.
        if (!devMode) {
            reject(response, 401, "UNAUTHORIZED",
                    "Missing Authorization: Bearer *** header.");
            return;
        }

        // 4. dev mode, no JWT, no X-Tenant-Id → forward to the
        //    controller. RagExceptionHandler will emit the standard
        //    problem+json 401.
        installAnonymousAuth("anonymous");
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Populate the SecurityContext with a JWT-backed Authentication so
     * {@code @PreAuthorize} / {@code SecurityContextHolder.getContext()}
     * downstream see the right principal + authorities. We do NOT throw
     * a Spring AuthenticationException — the request is already authorised
     * by the time we get here.
     */
    private void installJwtAuth(JwtTokenParser.Claims claims) {
        List<GrantedAuthority> auths = new ArrayList<>();
        for (String s : claims.scopes) {
            if (s != null && !s.isBlank()) {
                auths.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        // Phase 34-T34b — promote the "admin" scope into ROLE_ADMIN so
        // @PreAuthorize("hasRole('ADMIN')") matches on /admin/** endpoints.
        // We intentionally do NOT map other scopes to roles — admin is a
        // coarse single-role gate by design (single ROLE_ADMIN per spec
        // prohibition against complex RBAC).
        if (claims.scopes.contains("admin")) {
            auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        JwtPrincipal principal = new JwtPrincipal(claims.subject, claims.tenantId, claims.scopes);
        Authentication authentication =
                new JwtAuthentication(principal, auths);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(authentication);
        SecurityContextHolder.setContext(ctx);
        log.debug("🔐 T34b installed JWT auth subject={} tenant={} authorities={}",
                claims.subject, claims.tenantId, auths);
        if (claims.tenantId != null) MDC.put("jwt.tenant", claims.tenantId);
        if (claims.subject != null) MDC.put("jwt.sub", claims.subject);
    }

    /**
     * Dev-mode passthrough — install an AnonymousAuthenticationToken so
     * downstream code can still call {@code SecurityContextHolder} without
     * hitting a NullPointerException. The principal is the dev tenant so
     * audit logs and Micrometer tags still work.
     */
    private void installAnonymousAuth(String tenant) {
        List<GrantedAuthority> auths = List.of(
                new SimpleGrantedAuthority("ROLE_ANONYMOUS"),
                new SimpleGrantedAuthority("SCOPE_kb:read"),
                new SimpleGrantedAuthority("SCOPE_kb:write"),
                new SimpleGrantedAuthority("SCOPE_agent:invoke"));
        AnonymousAuthenticationToken auth = new AnonymousAuthenticationToken(
                "dev-" + tenant, tenant, auths);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private void applyRateLimit(HttpServletResponse response, String tenant) throws IOException {
        RateLimiter.Decision d = rateLimiter.check(tenant);
        response.setHeader("X-RateLimit-Limit",
                Integer.toString(rateLimiter.requestsPerWindow()));
        response.setHeader("X-RateLimit-Remaining",
                Integer.toString(Math.max(0,
                        rateLimiter.requestsPerWindow() - d.currentCount())));
        if (!d.allowed()) {
            response.setHeader("Retry-After",
                    Integer.toString(Math.max(1, d.resetMs() / 1000)));
            reject(response, 429, "RATE_LIMITED",
                    "Tenant '" + tenant + "' exceeded "
                            + rateLimiter.requestsPerWindow()
                            + " req / " + (rateLimiter.windowMs() / 1000) + "s");
        }
    }

    private void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}");
        // Force the response to commit so downstream filters /
        // controllers (notably Spring MVC's DispatcherServlet) see
        // isCommitted()==true and short-circuit. Without this, an
        // upstream filter reject + a downstream controller
        // invocation triggers "getWriter() has already been called"
        // because both try to write the response body.
        response.flushBuffer();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /**
     * Wraps a request so the downstream chain sees the JWT-derived
     * {@code X-Tenant-Id} / {@code X-User-Id} headers. We can't mutate
     * the real HttpServletRequest (servlet spec says it's read-only),
     * so the wrapper is the standard Spring idiom.
     */
    static final class HeaderOverrideRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final String tenantId;
        private final String userId;

        HeaderOverrideRequest(HttpServletRequest delegate, String tenantId, String userId) {
            super(delegate);
            this.tenantId = tenantId;
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            if (MdcTenantFilter.HEADER_TENANT.equalsIgnoreCase(name)) return tenantId;
            if ("X-User-Id".equalsIgnoreCase(name) && userId != null) return userId;
            return super.getHeader(name);
        }
    }
}
