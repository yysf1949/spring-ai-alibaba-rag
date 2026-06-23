package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * Agent 找不到指定工具时抛出。
 *
 * <p>继承 {@code RagException} 以复用现有的
 * {@code RagExceptionHandler}（{@code rag-app/web/RagExceptionHandler}）。
 * HTTP 状态码：404。</p>
 */
public class ToolNotFoundException extends RagException {

    public ToolNotFoundException(String toolName) {
        super("Tool not found: " + toolName);
    }
}