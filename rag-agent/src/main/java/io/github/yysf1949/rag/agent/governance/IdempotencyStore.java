package io.github.yysf1949.rag.agent.governance;

/**
 * 幂等结果存储 — 治理层用。
 *
 * <p>{@code putIfAbsent} 语义：第一次调用记入并返回 {@code PutResult.isFirst()=true}
 * + 写入的值，后续相同 key 调用返回 {@code PutResult.isReplay()=true} + 上次缓存的结果值。</p>
 */
public interface IdempotencyStore {

    PutResult putIfAbsent(IdempotencyKey key, Object value);

    /**
     * 写回结果值（key 已存在时用真实结果覆盖之前的 null 占位）。
     *
     * <p>配合 {@link #putIfAbsent} 的典型用法：第一次 {@code putIfAbsent(key, null)}
     * 占位拿到锁，业务完成后用 {@code replace(key, resultValue)} 把真实结果写回 —
     * 第二次同 key 调用 {@code putIfAbsent} 触发 REPLAY 时能拿回这个值。</p>
     */
    void replace(IdempotencyKey key, Object value);

    /** putIfAbsent 返回值。 */
    record PutResult(OutcomeKind outcome, Object value) {
        public enum OutcomeKind { FIRST, REPLAY }
        public boolean isFirst() { return outcome == OutcomeKind.FIRST; }
        public boolean isReplay() { return outcome == OutcomeKind.REPLAY; }
    }
}