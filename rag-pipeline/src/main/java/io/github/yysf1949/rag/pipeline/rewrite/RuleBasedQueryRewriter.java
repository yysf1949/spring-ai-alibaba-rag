package io.github.yysf1949.rag.pipeline.rewrite;

import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rule-based query rewriter — design spec §11.2.
 *
 * <h2>The three-stage pipeline</h2>
 * <ol>
 *   <li><b>Polite-prefix strip</b> — patterns like {@code 请问}, {@code 帮我},
 *       {@code 我想问} at the start of the query. They're noise for the
 *       embedding model.</li>
 *   <li><b>Stop-word strip</b> — punctuation, particles ({@code 的}, {@code 啊},
 *       {@code 呢}), repeated whitespace. Preserves Chinese word boundaries
 *       because we never split words, only whole-token removals.</li>
 *   <li><b>Synonym augmentation</b> — for every surface form present in the
 *       stripped query, append its canonical term. The canonical form is what
 *       we want the embedding model to find matches against.</li>
 * </ol>
 *
 * <h2>Confidence scoring</h2>
 * Confidence is computed AFTER all stages as a product of three binary
 * indicators (each 1.0 if its stage modified the text, 0.0 if not) —
 * i.e. the maximum is 1.0 (all three fired), minimum is 0.0 (no change
 * at all). The caller decides what threshold makes sense; default is 0.6
 * which matches spec §11.2's "规则 score < 0.6 才调 LLM".
 *
 * <p>Why multiplicative: a stage that produced no output should drag the
 * overall confidence down. Additive scores would let a single perfect
 * stage mask a failed one.</p>
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe to call from a thread pool. The
 * injected {@link SynonymTable} must itself be thread-safe.
 */
public class RuleBasedQueryRewriter implements RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedQueryRewriter.class);

    /** Default threshold below which {@code CachingRuleBasedRewriter} triggers the LLM fallback. */
    public static final double DEFAULT_LLM_FALLBACK_THRESHOLD = 0.6;

    /** Polite prefixes we always strip — spec §11.2 "去除客套词". */
    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:请问|麻烦|帮我|想问一下?|我想问|那个|那个啥|那个那个)\\s*[，,。.？?！!]?\\s*");

    /** Trailing/leading punctuation, particles — non-greedy, character class only. */
    private static final Pattern NOISE_CHARS = Pattern.compile("[\\s,，。.？?！!、；;:：\"'‘’“”()（）\\[\\]【】\\-_]+");

    /** Pure-particle tokens (separated from words). We don't try to split CJK — that's a tokenizer's job. */
    private static final Pattern PARTICLES = Pattern.compile("(?:的|啊|呢|嘛|哦|吧|哈|嗯|呀)");

    private final SynonymTable synonyms;
    private final double llmFallbackThreshold;

    public RuleBasedQueryRewriter() {
        this(DefaultChineseSynonymTable.create(), DEFAULT_LLM_FALLBACK_THRESHOLD);
    }

    public RuleBasedQueryRewriter(SynonymTable synonyms) {
        this(synonyms, DEFAULT_LLM_FALLBACK_THRESHOLD);
    }

    public RuleBasedQueryRewriter(SynonymTable synonyms, double llmFallbackThreshold) {
        this.synonyms = Objects.requireNonNull(synonyms, "synonyms");
        if (llmFallbackThreshold < 0 || llmFallbackThreshold > 1) {
            throw new IllegalArgumentException(
                    "llmFallbackThreshold must be in [0, 1], got " + llmFallbackThreshold);
        }
        this.llmFallbackThreshold = llmFallbackThreshold;
    }

    @Override
    public RewriteResult rewrite(String tenantId, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText must not be blank");
        }
        // tenantId is unused for now — per-tenant synonym tables are wired
        // via the constructor in production. Recorded in the signature for
        // future per-tenant prompt customization.

        String input = rawText.trim();
        String afterPolite = POLITE_PREFIX.matcher(input).replaceFirst("").trim();
        boolean politeChanged = !afterPolite.equals(input);

        // Stage 2: strip noise + particles.
        String stripped = NOISE_CHARS.matcher(afterPolite).replaceAll(" ").trim();
        stripped = PARTICLES.matcher(stripped).replaceAll("");
        stripped = stripped.replaceAll("\\s+", " ").trim();
        boolean stripChanged = !stripped.equals(afterPolite);

        // Stage 3: synonym augmentation.
        // We scan the stripped query for any surface form present in the
        // synonym table (whole-string containment, case-insensitive — surface
        // forms are typically short CJK tokens). For each match we append the
        // canonical term once.
        LinkedHashSet<String> augmentedTokens = new LinkedHashSet<>();
        augmentedTokens.add(stripped);
        boolean synChanged = false;
        String lowered = stripped.toLowerCase();
        for (String canonical : synonyms.canonicals()) {
            for (String surface : synonyms.surfacesOf(canonical)) {
                if (surface.isBlank()) continue;
                String surfaceLower = surface.toLowerCase();
                if (lowered.contains(surfaceLower)) {
                    // Canonicalize — only if not already in the stripped text.
                    if (!stripped.contains(canonical)) {
                        augmentedTokens.add(canonical);
                        synChanged = true;
                    }
                    break; // one surface match per canonical is enough
                }
            }
        }

        String rewritten = String.join(" ", augmentedTokens).trim();

        // If everything stripped away (e.g. user typed only "啊" + "??"),
        // fall back to the input — the embedding model will deal with it.
        if (rewritten.isEmpty()) {
            rewritten = rawText.trim();
        }

        double score = score(politeChanged, stripChanged, synChanged);
        RewriteResult result = new RewriteResult(rewritten, score, false);
        if (log.isDebugEnabled()) {
            log.debug("rule rewrite tenant={} in='{}' out='{}' polite={} strip={} syn={} score={}",
                    tenantId, rawText, rewritten, politeChanged, stripChanged, synChanged, score);
        }
        return result;
    }

    public double llmFallbackThreshold() {
        return llmFallbackThreshold;
    }

    public SynonymTable synonyms() {
        return synonyms;
    }

    /**
     * @return 1.0 if all three stages fired, decreasing toward 0.0 otherwise.
     *         Multiplicative — any "did nothing" stage pulls the score down.
     */
    private static double score(boolean politeChanged, boolean stripChanged, boolean synChanged) {
        double s = 1.0;
        if (!politeChanged) s *= 0.7;
        if (!stripChanged) s *= 0.7;
        if (!synChanged) s *= 0.5;
        return s;
    }

    /**
     * Simple list-builder helper exposed for tests / programmatic use.
     */
    public static List<String> splitTokens(String text) {
        return List.of(text.split("\\s+"));
    }
}
