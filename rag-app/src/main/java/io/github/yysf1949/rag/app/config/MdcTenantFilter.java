package io.github.yysf1949.rag.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates the SLF4J MDC with {@code tenantId} and {@code requestId} so
 * every log line inside the request is automatically annotated — spec §9.2.
 *
 * <p>The filter resolves {@code tenantId} from the {@code X-Tenant-Id}
 * request header. The HTTP layer should never trust a body-supplied
 * {@code tenantId} (see MULTI_TENANT.md §8) — only the gateway-resolved
 * header value is authoritative.</p>
 *
 * <p>{@code requestId} is generated if the client did not provide one
 * (header {@code X-Request-Id}). The same value is echoed back in the
 * response header for correlation.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcTenantFilter extends OncePerRequestFilter {

    public static final String MDC_TENANT = "tenant";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String tenant = request.getHeader(HEADER_TENANT);
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        if (tenant != null && !tenant.isBlank()) {
            MDC.put(MDC_TENANT, tenant);
        }
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /**
     * Read the {@code X-Request-Id} from the request, or generate a new
     * UUID if the client did not supply one. Safe to call from controllers
     * after the filter has run; if the filter has not run (some test
     * contexts) this still returns a stable value rather than null.
     *
     * <p>Used by audit-emitting endpoints so the {@code requestId} on the
     * audit event matches the {@code requestId} on the response header
     * and on every business log line in the request scope.</p>
     */
    public static String requestId(HttpServletRequest request) {
        String rid = request.getHeader(HEADER_REQUEST_ID);
        if (rid == null || rid.isBlank()) {
            String mdc = MDC.get(MDC_REQUEST_ID);
            if (mdc != null && !mdc.isBlank()) {
                return mdc;
            }
            rid = UUID.randomUUID().toString();
        }
        return rid;
    }
}
