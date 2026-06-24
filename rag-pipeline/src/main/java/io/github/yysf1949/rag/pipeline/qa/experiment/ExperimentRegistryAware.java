package io.github.yysf1949.rag.pipeline.qa.experiment;

import java.util.Objects;

/**
 * Default {@link ExperimentAware} backed by an {@link ExperimentRegistry}.
 * Use this in production wiring; tests should construct their own to avoid
 * dragging in Micrometer.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentRegistryAware implements ExperimentAware {

    private final ExperimentRegistry registry;

    public ExperimentRegistryAware(ExperimentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public ExperimentVariant assignVariant(String experimentName, String subjectId) {
        var exp = registry.find(experimentName);
        if (exp.isEmpty()) {
            return null;
        }
        return ExperimentAssignment.assign(exp.get(), subjectId);
    }

    @Override
    public void recordExposure(String experimentName, ExperimentVariant variant) {
        registry.recorder(experimentName).recordExposure(variant);
    }

    @Override
    public void recordOutcome(String experimentName, ExperimentVariant variant, boolean positive) {
        var recorder = registry.recorder(experimentName);
        if (positive) {
            recorder.recordPositive(variant);
        } else {
            recorder.recordNegative(variant);
        }
    }
}
