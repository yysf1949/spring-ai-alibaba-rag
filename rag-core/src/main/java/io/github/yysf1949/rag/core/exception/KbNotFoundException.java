package io.github.yysf1949.rag.core.exception;

/**
 * The requested KB has never been published (or has been fully
 * deprecated and cleaned up). This is a <b>logical</b> error,
 * not an infrastructure one — the QA endpoint should surface it
 * as a graceful empty retrieval (200 + FALLBACK_RULE), not a 503.
 *
 * <p>Spec §10 distinction: {@link VectorStoreUnavailableException}
 * means "Redis is down, retry later"; {@link KbNotFoundException}
 * means "the kbId you asked for has no published data". The QA
 * service handles them differently:
 * <ul>
 *   <li>{@code VectorStoreUnavailableException} → 503 + Retry-After</li>
 *   <li>{@code KbNotFoundException} → 200 + FALLBACK_RULE text</li>
 * </ul>
 */
public class KbNotFoundException extends RagException {

    public KbNotFoundException(String message) {
        super(message);
    }
}
