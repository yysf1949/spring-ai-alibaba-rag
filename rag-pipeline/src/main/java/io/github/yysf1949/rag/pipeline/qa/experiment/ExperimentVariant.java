package io.github.yysf1949.rag.pipeline.qa.experiment;

/**
 * A single arm of an A/B experiment. Variants are immutable, hashable,
 * and carry an optional configuration override that the pipeline can read
 * to alter behaviour for that arm (e.g. a different topK, a different
 * rerank model, or a different rewrite rule).
 *
 * <p>Two variants are pre-defined as enums — {@link #CONTROL} and
 * {@link #TREATMENT_A}. Custom arms can be created via {@link #custom(String)}
 * for richer experiments.</p>
 *
 * <h2>Identifier rules</h2>
 * Identifiers must match {@code [A-Za-z0-9_-]{1,32}}. The validation
 * intentionally rejects whitespace, dots, and non-ASCII characters so
 * that variant ids round-trip cleanly through Prometheus label values,
 * Micrometer tags, and HTTP headers without escaping headaches.
 *
 * <p>Phase 39 / R14. Design spec §14 — A/B experimentation.</p>
 */
public record ExperimentVariant(String id, double weight) {

    public static final String ID_PATTERN = "[A-Za-z0-9_-]{1,32}";

    public static final ExperimentVariant CONTROL = new ExperimentVariant("control", 1.0);
    public static final ExperimentVariant TREATMENT_A = new ExperimentVariant("treatment_a", 1.0);

    public ExperimentVariant {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException(
                    "ExperimentVariant id must match " + ID_PATTERN + " but was: " + id);
        }
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException(
                    "ExperimentVariant weight must be a non-negative finite number but was: " + weight);
        }
    }

    /** Convenience factory for custom variants — weight defaults to 1.0. */
    public static ExperimentVariant custom(String id) {
        return new ExperimentVariant(id, 1.0);
    }
}