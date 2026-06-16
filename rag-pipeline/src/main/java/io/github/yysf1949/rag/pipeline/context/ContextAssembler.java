package io.github.yysf1949.rag.pipeline.context;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the LLM prompt out of the reranked chunk list — design spec
 * §13.10 + §15.3 (PII redaction).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>For each chunk in order (already reranked, score-desc):
 *     <ol type="a">
 *       <li>Always emit a header line {@code [N] title > sectionPath (sourceUri)}
 *           — these three metadata fields are GUARANTEED to fit no matter
 *           how tight the budget gets (spec §13.10:
 *           "永不截断元数据").</li>
 *       <li>Add the body (after PII redaction) as long as the running token
 *           counter stays within {@code tokenBudget}.</li>
 *       <li>If the body alone would overflow, truncate to whatever fits and
 *           append {@code …[truncated]} — the header stays intact so the
 *           {@link Citation} is still recoverable.</li>
 *       <li>If even the header line would not fit, the chunk is dropped
 *           (very low-budget scenarios). A warn is logged.</li>
 *     </ol>
 *   </li>
 *   <li>Produce a {@link AssembledPrompt} carrying the rendered text + the
 *       {@link Citation} list (one per chunk that made it in, in order).</li>
 * </ol>
 *
 * <h2>Why metadata is non-negotiable</h2>
 * The LLM is told to cite by {@code [N]} marker. If we truncated the title
 * away, the citation would point at nothing and Grounded Rate (spec §9.3)
 * would crater. So headers are unconditional, bodies are elastic.
 *
 * <h2>PII redaction (§15.3)</h2>
 * Three patterns redacted before the body is fed to the budget:
 * <ul>
 *   <li>Chinese ID card: {@code \d{15,18}}</li>
 *   <li>Mobile phone: {@code 1[3-9]\d{9}}</li>
 *   <li>Bank card: {@code \d{16,19}} (lenient — production should swap in
 *       a Luhn validator; the spec says "过滤正则", so a regex pass is
 *       spec-compliant).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe to call from a thread pool. The
 * injected {@link TokenCounter} and {@link PromptTemplate} must themselves
 * be thread-safe.
 */
public final class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    /** Spec §7.4 default — 4000 tokens. */
    public static final int DEFAULT_TOKEN_BUDGET = 4000;

    /** Marker appended to a body that was truncated to fit the budget. */
    public static final String TRUNCATION_MARKER = " …[truncated]";

    private final TokenCounter tokenCounter;
    private final PromptTemplate promptTemplate;
    private final SensitiveDataRedactor redactor;

    public ContextAssembler() {
        this(new ApproxCharTokenCounter(), new DefaultPromptTemplate(), new DefaultSensitiveDataRedactor());
    }

    public ContextAssembler(TokenCounter tokenCounter,
                            PromptTemplate promptTemplate,
                            SensitiveDataRedactor redactor) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * @param reranked     post-rerank candidates in score-desc order (spec §7.4
     *                     says usually N=5 but we don't enforce here).
     * @param queryText    the user's question (or rewritten form) — embedded
     *                     into the prompt so the LLM knows what to answer.
     * @param tokenBudget  total token cap; must be {@code > 0}. Use
     *                     {@link #DEFAULT_TOKEN_BUDGET} unless the operator
     *                     has a reason to lower it.
     * @return assembled prompt — never null, never {@code finalText()} null
     *         (may be the empty prompt template body if every chunk was
     *         dropped, see the algorithm notes).
     */
    public AssembledPrompt assemble(List<Chunk> reranked, String queryText, int tokenBudget) {
        if (reranked == null) {
            reranked = List.of();
        }
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be > 0, got " + tokenBudget);
        }

        // Per-call state.
        StringBuilder body = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        int used = 0;
        boolean anyTruncated = false;

        for (int i = 0; i < reranked.size(); i++) {
            Chunk c = reranked.get(i);
            int marker = i + 1; // [1], [2], …

            String header = promptTemplate.header(marker, c);
            int headerTokens = tokenCounter.count(header);

            if (used + headerTokens > tokenBudget) {
                // Even the metadata header doesn't fit — we've completely
                // exhausted the budget. Drop the rest of the chunks and stop;
                // smaller headers from later chunks would only crowd the
                // citation list further without adding signal.
                log.warn("ContextAssembler: stopping after {} chunks; remaining budget {} < header size {} "
                                + "for chunk {} ({})",
                        citations.size(), tokenBudget - used, headerTokens, c.chunkId(), c.sectionPath());
                break;
            }
            body.append(header);
            used += headerTokens;

            // Build redacted body.
            String redactedBody = redactor.redact(c.content());
            int bodyTokens = tokenCounter.count(redactedBody);

            if (used + bodyTokens <= tokenBudget) {
                // Whole body fits.
                body.append(redactedBody);
                if (!redactedBody.endsWith("\n")) {
                    body.append('\n');
                }
                used += bodyTokens;
            } else if (bodyTokens == 0) {
                // Empty body — header-only citation, no truncation needed.
                // No-op here; citation is recorded below.
            } else {
                // Compress to whatever fits. The truncation marker counts
                // against the budget too — we subtract it BEFORE compressing
                // so the marker never starves a later chunk's header.
                int markerTokens = tokenCounter.count(TRUNCATION_MARKER);
                int remainingForBody = tokenBudget - used - markerTokens;
                if (remainingForBody < 0) {
                    // Header alone + marker would overflow; just emit the
                    // marker alone and continue to give later chunks a shot.
                    body.append(TRUNCATION_MARKER);
                    body.append('\n');
                    used += markerTokens;
                    anyTruncated = true;
                } else {
                    String compressed = compressToTokenBudget(redactedBody, remainingForBody);
                    body.append(compressed);
                    body.append(TRUNCATION_MARKER);
                    body.append('\n');
                    used += tokenCounter.count(compressed) + markerTokens;
                    anyTruncated = true;
                }
            }

            // Record citation (always; even truncated chunks remain cite-able).
            citations.add(new Citation(c.chunkId(), c.title(), c.sectionPath(), c.sourceUri(), 1.0));
        }

        String fullPrompt = promptTemplate.render(queryText, body.toString());
        int promptTokens = tokenCounter.count(fullPrompt);

        return new AssembledPrompt(fullPrompt, citations, used, promptTokens, anyTruncated);
    }

    /**
     * Greedy character-trim to fit {@code tokenBudget}. We cut from the tail
     * to keep the start of the chunk (which is usually the most relevant
     * part — chunks are split so the head carries the heading context).
     *
     * @return prefix of {@code body} whose token cost is {@code <= tokenBudget}.
     *         If even one character doesn't fit, returns the empty string
     *         (the caller will skip emitting any body and just keep the header).
     */
    private String compressToTokenBudget(String body, int tokenBudget) {
        // Start with the whole body and walk back; cheap because we always
        // shrink. TokenCounter is cheap (chars/2 or similar), so a single
        // O(n) walk beats binary search for typical short bodies.
        if (tokenCounter.count(body) <= tokenBudget) {
            return body;
        }
        // Walk back from the end, two characters at a time (saves a few
        // iterations without breaking the budget accuracy for ASCII+1.5x chars).
        int hi = body.length();
        int lo = 0;
        // First quick cut: linear shrink by 10% until under budget.
        while (hi > lo && tokenCounter.count(body.substring(0, hi)) > tokenBudget) {
            hi = lo + (int) Math.ceil((hi - lo) * 0.9);
        }
        // Then refine to the exact boundary.
        while (hi > lo && tokenCounter.count(body.substring(0, hi)) > tokenBudget) {
            hi--;
        }
        return body.substring(0, hi);
    }

    // ─── inner types ───────────────────────────────────────────────────────

    /**
     * Result of an {@link #assemble} call.
     *
     * @param fullPrompt     ready-to-feed prompt text
     * @param citations      one per chunk that made it in (header-preserved
     *                       even when the body was truncated)
     * @param bodyTokens     tokens spent on chunk bodies + headers
     * @param promptTokens   token cost of {@code fullPrompt} (includes the
     *                       template overhead around body)
     * @param anyTruncated   true if any chunk's body had to be trimmed
     */
    public record AssembledPrompt(
            String fullPrompt,
            List<Citation> citations,
            int bodyTokens,
            int promptTokens,
            boolean anyTruncated
    ) {
        public AssembledPrompt {
            fullPrompt = fullPrompt == null ? "" : fullPrompt;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** True iff at least one chunk made it in (header or body). */
        public boolean hasCitations() {
            return !citations.isEmpty();
        }
    }
}
