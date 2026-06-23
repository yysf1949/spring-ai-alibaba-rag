package io.github.yysf1949.rag.app.config;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Populates the SLF4J MDC with {@code tenantId} and {@code requestId} so
 * every log line inside the request is automatically annotated — spec §9.2.
 *
 * <p><b>Tenant header verification (HMAC-SHA256)</b>:</p>
 * <p>The filter verifies the {@code X-Tenant-Id} header against a companion
 * {@code X-Tenant-Signature} header using HMAC-SHA256. If the signature is
 * missing or invalid, the request is rejected with HTTP 401. This prevents
 * spoofing the tenant identity by directly setting the header.</p>
 *
 * <p>Signature computation: {@code HMAC-SHA256(shared_secret, tenantId)}</p>
 * <p>The shared secret is configured via {@code rag.tenant.shared-secret}.</p>
 *
 * <p>When {@code rag.tenant.shared-secret} is empty or blank, signature
 * verification is DISABLED (dev/test mode only). A warning is logged at
 * startup.</p>
 *
 * <p>{@code requestId} is generated if the client did not provide one
 * (header {@code X-Request-Id}). The same value is echoed back in the
 * response header for correlation.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcTenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MdcTenantFilter.class);

    public static final String MDC_TENANT = "tenant";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_TENANT_SIGNATURE = "X-Tenant-Signature";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final String HMAC_ALGO = "HmacSHA256";

    private final String sharedSecret;
    private final boolean verificationEnabled;

    public MdcTenantFilter(
            @Value("${rag.tenant.shared-secret:}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
        this.verificationEnabled = sharedSecret != null && !sharedSecret.isBlank();
        if (!verificationEnabled) {
            log.warn("⚠️ Tenant signature verification DISABLED (rag.tenant.shared-secret is blank). "
                    + "Set rag.tenant.shared-secret in production to prevent tenant spoofing.");
        }
    }

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

        // --- Tenant signature verification ---
        if (tenant != null && !tenant.isBlank()) {
            if (verificationEnabled) {
                String signature = request.getHeader(HEADER_TENANT_SIGNATURE);
                if (!verifySignature(tenant, signature)) {
                    log.warn("Tenant signature verification failed for tenant='{}', ip={}",
                            tenant, request.getRemoteAddr());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"error\":\"INVALID_TENANT_SIGNATURE\","
                            + "\"message\":\"X-Tenant-Signature verification failed\"}");
                    return; // reject request
                }
            }
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
     * Verify HMAC-SHA256(shared_secret, tenantId) == signature (hex-encoded).
     * Uses constant-time comparison to prevent timing attacks.
     */
    private boolean verifySignature(String tenant, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] expected = mac.doFinal(tenant.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
            log.error("HMAC verification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generate HMAC-SHA256 signature for a tenant ID (utility for gateway/proxy).
     */
    public static String computeSignature(String sharedSecret, String tenantId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] sig = mac.doFinal(tenantId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /**
     * Read the {@code X-Request-Id} from the request, or generate a new
     * UUID if the client did not supply one. Safe to call from controllers
     * after the filter has run.
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
