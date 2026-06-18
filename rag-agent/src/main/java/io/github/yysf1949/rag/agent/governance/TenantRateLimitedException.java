package io.github.yysf1949.rag.agent.governance;

/**
 * 租户级限流触发 — 单一租户打满 QPS 时抛出，由 AgentController 转 429。
 */
public class TenantRateLimitedException extends RuntimeException {

    private final String tenantId;

    public TenantRateLimitedException(String tenantId, String message) {
        super(String.format("Tenant [%s] rate limited: %s", tenantId, message));
        this.tenantId = tenantId;
    }

    public String tenantId() { return tenantId; }
}