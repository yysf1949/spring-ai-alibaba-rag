package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * 工具风险被门控拒绝 — HTTP 状态码：403。
 */
public class ToolRiskDeniedException extends RagException {
    public ToolRiskDeniedException(String message) {
        super(message);
    }
}
