package io.github.yysf1949.rag.pipeline.qa.experiment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-experiment outcome counter. Backed by Micrometer counters for
 * Prometheus / Grafana scrape and by in-process {@link LongAdder}s for
 * the in-JVM significance test.
 *
 * <h2>Why a separate in-process counter?</h2>
 * Micrometer counters are designed for scrape-time monotonicity, not
 * for real-time arithmetic inside the application. We mirror increments
 * to {@link LongAdder}s so the {@link ExperimentSignificanceTester}
 * can compute a 2×2 contingency chi-square without scraping the
 * registry.
 *
 * <h2>Counter tags</h2>
 * Each counter is tagged with {@code experiment=<name>} and
 * {@code variant=<id>}. Outcomes carry an additional
 * {@code outcome=positive|negative} tag.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentMetricsRecorder {

    private final String experimentName;
    private final ConcurrentHashMap<String, LongAdder> exposures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> positives = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> negatives = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    ExperimentMetricsRecorder(MeterRegistry meterRegistry, Experiment experiment) {
        this.experimentName = experiment.name();
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        for (ExperimentVariant v : experiment.variants()) {
            exposures.put(v.id(), new LongAdder());
            positives.put(v.id(), new LongAdder());
            negatives.put(v.id(), new LongAdder());
            Counter.builder("rag.experiment.exposures")
                    .description("Number of users exposed to each experiment variant")
                    .tags(ExperimentRegistry.tags(experimentName, v))
                    .register(meterRegistry);
            Counter.builder("rag.experiment.outcomes")
                    .description("Number of positive outcomes recorded per variant")
                    .tag("outcome", "positive")
                    .tags(ExperimentRegistry.tags(experimentName, v))
                    .register(meterRegistry);
            Counter.builder("rag.experiment.outcomes")
                    .description("Number of negative outcomes recorded per variant")
                    .tag("outcome", "negative")
                    .tags(ExperimentRegistry.tags(experimentName, v))
                    .register(meterRegistry);
        }
    }

    /** Increment exposure for the given variant. */
    public void recordExposure(ExperimentVariant variant) {
        exposures.computeIfAbsent(variant.id(), k -> new LongAdder()).increment();
        Counter.builder("rag.experiment.exposures")
                .tags(ExperimentRegistry.tags(experimentName, variant))
                .register(meterRegistry)
                .increment();
    }

    /** Increment a positive outcome (the answer was useful / click-thru / cite-rate hit). */
    public void recordPositive(ExperimentVariant variant) {
        positives.computeIfAbsent(variant.id(), k -> new LongAdder()).increment();
        Counter.builder("rag.experiment.outcomes")
                .tag("outcome", "positive")
                .tags(ExperimentRegistry.tags(experimentName, variant))
                .register(meterRegistry)
                .increment();
    }

    /** Increment a negative outcome (the answer failed / user down-rated / no cite). */
    public void recordNegative(ExperimentVariant variant) {
        negatives.computeIfAbsent(variant.id(), k -> new LongAdder()).increment();
        Counter.builder("rag.experiment.outcomes")
                .tag("outcome", "negative")
                .tags(ExperimentRegistry.tags(experimentName, variant))
                .register(meterRegistry)
                .increment();
    }

    /** Exposure count for a variant. */
    public long exposures(ExperimentVariant variant) {
        LongAdder a = exposures.get(variant.id());
        return a == null ? 0L : a.sum();
    }

    /** Positive-outcome count for a variant. */
    public long positives(ExperimentVariant variant) {
        LongAdder a = positives.get(variant.id());
        return a == null ? 0L : a.sum();
    }

    /** Negative-outcome count for a variant. */
    public long negatives(ExperimentVariant variant) {
        LongAdder a = negatives.get(variant.id());
        return a == null ? 0L : a.sum();
    }

    /** Total outcomes (positive + negative) for a variant. */
    public long totalOutcomes(ExperimentVariant variant) {
        return positives(variant) + negatives(variant);
    }
}