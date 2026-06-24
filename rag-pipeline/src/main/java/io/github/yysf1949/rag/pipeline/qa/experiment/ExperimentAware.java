package io.github.yysf1949.rag.pipeline.qa.experiment;

/**
 * Optional port that {@link io.github.yysf1949.rag.pipeline.qa.QAServiceImpl}
 * looks up at runtime to decide which A/B variant (if any) a given query
 * belongs to. The default no-op implementation (when no registry is
 * configured) returns {@code null}, signalling "no experiment applies".
 *
 * <h2>Why a port and not a direct {@link ExperimentRegistry} injection?</h2>
 * Keeps {@code rag-pipeline} hermetic — the {@code QAService} constructor
 * signature stays unchanged, and Spring wiring in {@code rag-app} can pass
 * in the registry without leaking Spring types into the pipeline module.
 *
 * <p>Phase 39 / R14.</p>
 */
public interface ExperimentAware {

    /**
     * Assign a variant for {@code (experimentName, subjectId)}.
     *
     * @param experimentName experiment to look up; if no experiment is
     *                       registered under this name, return {@code null}
     * @param subjectId      opaque subject id (userId)
     * @return the assigned variant, or {@code null} if no experiment applies
     */
    ExperimentVariant assignVariant(String experimentName, String subjectId);

    /**
     * Record an exposure for {@code (experimentName, variant)} — called
     * once per query that was actually routed to the variant.
     */
    void recordExposure(String experimentName, ExperimentVariant variant);

    /**
     * Record an outcome (positive or negative) for {@code (experimentName, variant)}.
     */
    void recordOutcome(String experimentName, ExperimentVariant variant, boolean positive);

    /**
     * No-op default — keeps existing pipelines functional without A/B wiring.
     */
    ExperimentAware NOOP = new ExperimentAware() {
        @Override
        public ExperimentVariant assignVariant(String experimentName, String subjectId) {
            return null;
        }
        @Override
        public void recordExposure(String experimentName, ExperimentVariant variant) {
            // no-op
        }
        @Override
        public void recordOutcome(String experimentName, ExperimentVariant variant, boolean positive) {
            // no-op
        }
    };
}
