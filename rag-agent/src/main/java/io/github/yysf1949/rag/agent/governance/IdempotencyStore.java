package io.github.yysf1949.rag.agent.governance;

/**
 * 幂等结果存储 — 治理层用。
 *
 * <p>{@code putIfAbsent} 语义：第一次调用记入并返回 {@code PutResult.isFirst()=true}
 * + 写入的值，后续相同 key 调用返回 {@code PutResult.isReplay()=true} + 上次缓存的结果值。</p>
 */
public interface IdempotencyStore {

    PutResult putIfAbsent(IdempotencyKey key, Object value);

    /** putIfAbsent 返回值。 */
    record PutResult(OutcomeKind outcome, Object value) {
        public enum OutcomeKind { FIRST, REPLAY }
        public boolean isFirst() { return outcome == OutcomeKind.FIRST; }
        public boolean isReplay() { return outcome == OutcomeKind.REPLAY; }
    }
}