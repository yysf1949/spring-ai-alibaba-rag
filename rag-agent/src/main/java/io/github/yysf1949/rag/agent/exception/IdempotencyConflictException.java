package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * 幂等键冲突 — 同一 key 第二次调用带不同参数时抛出。
 *
 * <p>本 Phase 不强制做"参数一致性"校验，留待 Phase 10。
 * 当前实现：第二次同 key 直接返回上次结果（REPLAY），不抛此异常。</p>
 */
public class IdempotencyConflictException extends RagException {
    public IdempotencyConflictException(String key) {
        super("Idempotency conflict for key: " + key);
    }
}