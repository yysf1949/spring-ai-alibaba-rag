package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.exception.IdempotencyConflictException;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent 异常 → HTTP 状态码映射。
 *
 * <p>对齐项目既有 {@code RagExceptionHandler} 风格（{@code ProblemDetail} + 422/403/404）。</p>
 */
@RestControllerAdvice
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
}
