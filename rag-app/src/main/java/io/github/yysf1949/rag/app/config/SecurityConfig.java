package io.github.yysf1949.rag.app.config;

import io.github.yysf1949.rag.app.security.JwtTenantFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security configuration with API key authentication.
 *
 * <p>When {@code rag.security.api-key} is set (non-blank), all API endpoints
 * (except actuator health/readiness and Swagger UI) require the header
 * {@code X-API-Key} to match the configured key.</p>
 *
 * <p>When {@code rag.security.api-key} is blank or not set, security is
 * effectively disabled (all requests are permitted) — suitable for dev/test.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            @Value("${rag.security.api-key:}") String apiKey,
            ApiKeyAuthenticationFilter apiKeyFilter,
            JwtTenantFilter jwtTenantFilter) throws Exception {

        boolean authEnabled = apiKey != null && !apiKey.isBlank();

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Phase 34 (R5+R6) — JwtTenantFilter runs FIRST in the chain so
        // JWT verification (or dev-mode tenant passthrough) happens before
        // Spring Security's own authentication filters. The filter handles
        // 401 / 429 short-circuits internally, so successful requests fall
        // through to the API key filter (if enabled) and then to
        // authorization rules.
        http.addFilterBefore(jwtTenantFilter, UsernamePasswordAuthenticationFilter.class);

        if (authEnabled) {
            log.info("🔒 API key authentication ENABLED (rag.security.api-key is set)");
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/info").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated()
            ).addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            log.warn("⚠️ API key authentication DISABLED (rag.security.api-key is not set). "
                    + "All endpoints are open. Set rag.security.api-key in production.");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    /**
     * Filter that checks {@code X-API-Key} header against the configured key.
     */
    @Component
    public static class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
        private static final String HEADER = "X-API-Key";

        private final String expectedKey;
        private final boolean enabled;

        public ApiKeyAuthenticationFilter(
                @Value("${rag.security.api-key:}") String apiKey) {
            this.expectedKey = apiKey;
            this.enabled = apiKey != null && !apiKey.isBlank();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {
            if (!enabled) {
                chain.doFilter(request, response);
                return;
            }

            String provided = request.getHeader(HEADER);
            if (provided == null || provided.isBlank()) {
                reject(response, "Missing X-API-Key header");
                return;
            }

            // Constant-time comparison to prevent timing attacks
            if (!MessageDigest.isEqual(
                    provided.getBytes(),
                    expectedKey.getBytes())) {
                log.warn("Invalid API key from IP: {}", request.getRemoteAddr());
                reject(response, "Invalid API key");
                return;
            }

            // Set authentication in SecurityContext
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new ApiKeyAuthentication(provided));
            SecurityContextHolder.setContext(ctx);

            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private void reject(HttpServletResponse response, String message) throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
        }
    }

    /**
     * Authentication token for API key-based auth.
     */
    public static class ApiKeyAuthentication implements Authentication {

        private final String key;
        private boolean authenticated = true;

        public ApiKeyAuthentication(String key) {
            this.key = key;
        }

        @Override public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_API"));
        }
        @Override public String getCredentials() { return key; }
        @Override public Object getDetails() { return null; }
        @Override public String getName() { return "api-key-user"; }
        @Override public Object getPrincipal() { return "api-key-user"; }
        @Override public boolean isAuthenticated() { return authenticated; }
        @Override public void setAuthenticated(boolean isAuthenticated) { this.authenticated = isAuthenticated; }
    }
}
