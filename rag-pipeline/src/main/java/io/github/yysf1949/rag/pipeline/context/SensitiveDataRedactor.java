package io.github.yysf1949.rag.pipeline.context;

/**
 * Strategy for stripping PII before it leaves the retrieval layer (spec
 * §15.3). The default implementation uses regex — production deployments
 * that need strict bank-card validation should swap in a Luhn-aware
 * variant via the {@link ContextAssembler} constructor.
 */
@FunctionalInterface
public interface SensitiveDataRedactor {

    /**
     * @return input with sensitive substrings replaced by redacted
     *         placeholders (e.g. {@code "***"} for an ID card). The default
     *         replacement keeps length parity so the LLM can still count
     *         digits in surrounding text without leaking.
     */
    String redact(String text);
}
