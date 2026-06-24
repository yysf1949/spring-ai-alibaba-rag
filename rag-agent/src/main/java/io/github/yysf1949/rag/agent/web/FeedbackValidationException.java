package io.github.yysf1949.rag.agent.web;

/**
 * Phase 40 T1 — 反馈参数校验失败异常.
 *
 * <p>用单独异常类型让 {@link AgentExceptionHandler} 映射到 400 Bad Request,
 * 不污染全局 IllegalArgumentException 处理 (其它端点可能依赖 500/422 行为).</p>
 */
public class FeedbackValidationException extends RuntimeException {

    public FeedbackValidationException(String message) {
        super(message);
    }
}