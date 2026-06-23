package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.exception.IdempotencyConflictException;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.TenantRateLimitedException;
import io.github.yysf1949.rag.agent.quota.TenantQuotaExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent 异常 → HTTP 状态码映射。
 *
 * <p>对齐项目既有 {@code RagExceptionHandler} 风格（{@code ProblemDetail} + 422/403/404）。</p>
 *
 * <h2>{@code @Order(HIGHEST_PRECEDENCE)} 的作用</h2>
 * <p>让 Agent 的具体异常处理器（{@code ToolRiskDeniedException} 等）优先于
 * {@code RagExceptionHandler.unexpected(RuntimeException)} 兜底 — 否则 L2 缺
 * idempotencyKey 的拒绝会变成 500 而不是 403。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentExceptionHandler {

    @ExceptionHandler(ToolNotFoundException.class)
    public ProblemDetail handleToolNotFound(ToolNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ToolRiskDeniedException.class)
    public ProblemDetail handleRiskDenied(ToolRiskDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(TenantRateLimitedException.class)
    public ProblemDetail handleTenantRateLimited(TenantRateLimitedException e) {
        // Phase 13a M3: 租户级 QPS 超限 → 429 Too Many Requests
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /**
     * Phase 40 T3: 月度配额超限 → 429 Too Many Requests.
     * 与 TenantRateLimitedException 走同一 HTTP 状态, 但 ProblemDetail body
     * 区分清楚 (QPS vs 月度) 便于调用方识别.
     */
    @ExceptionHandler(TenantQuotaExceededException.class)
    public ProblemDetail handleTenantQuotaExceeded(TenantQuotaExceededException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setProperty("tenantId", e.getTenantId());
        pd.setProperty("resource", e.getResource());
        pd.setProperty("currentUsage", e.getCurrentUsage());
        pd.setProperty("limit", e.getLimit());
        pd.setProperty("monthKey", e.getMonthKey());
        return pd;
    }

    /**
     * Phase 40 T1: 反馈参数非法 (thumb/rating/comment 全空) → 400 Bad Request.
     * 用 {@link FeedbackValidationException} 单独映射, 不影响其它 IllegalArgumentException 行为.
     */
    @ExceptionHandler(FeedbackValidationException.class)
    public ProblemDetail handleFeedbackValidation(FeedbackValidationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HandoffRequiredException.class)
    public ProblemDetail handleHandoffRequired(HandoffRequiredException e) {
        // Phase 13b M6: 业务规则命中"必须人工" → 422 Unprocessable Entity
        // 注：通常 DefaultAgentLoop 已 catch 并转为 HANDOFF_REQUIRED outcome（不进这里）；
        // 此映射兜底"直接调用 Tool / 测试场景"或 future HttpChannelAdapter 跳过编排层的边缘 case
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
