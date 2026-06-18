package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.exception.IdempotencyConflictException;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.TenantRateLimitedException;
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
}
