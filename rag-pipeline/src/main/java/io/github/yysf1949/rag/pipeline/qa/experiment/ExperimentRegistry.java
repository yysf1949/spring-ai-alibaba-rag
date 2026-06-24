package io.github.yysf1949.rag.pipeline.qa.experiment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of all active A/B experiments. Thread-safe, JVM-scoped,
 * and Micrometer-aware.
 *
 * <h2>Read path</h2>
 * <pre>{@code
 *   Experiment exp = registry.require("rag-retrieval-topk-v2");
 *   ExperimentVariant variant = ExperimentAssignment.assign(exp, query.userId());
 *   Integer topK = (Integer) exp.property("experiment.rag-retrieval-topk-v2.topK", 5);
 * }</pre>
 *
 * <h2>Write path</h2>
 * Experiments are registered at startup via {@link #register(Experiment)}.
 * Re-registering with the same name replaces the prior definition and
 * resets the {@link ExperimentMetricsRecorder} state for that experiment.
 *
 * <h2>Micrometer integration</h2>
 * The registry exposes two counters per experiment arm:
 * <ul>
 *   <li>{@code rag.experiment.exposures} — incremented when the pipeline
 *       hands a request to a variant</li>
 *   <li>{@code rag.experiment.outcomes} — incremented when the pipeline
 *       records an outcome (positive = answer was useful, negative = was not)</li>
 * </ul>
 * Both counters carry {@code experiment=<name>, variant=<id>} tags.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRegistry.class);

    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final Map<String, ExperimentMetricsRecorder> recorders = new ConcurrentHashMap<>();

    public ExperimentRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * Register or replace an experiment. Resets the metrics recorder for
     * the named experiment so prior bucket counts do not contaminate a
     * freshly-defined experiment.
     *
     * @return the recorder registered for the new experiment
     */
    public ExperimentMetricsRecorder register(Experiment experiment) {
        Objects.requireNonNull(experiment, "experiment");
        experiments.put(experiment.name(), experiment);
        ExperimentMetricsRecorder recorder = new ExperimentMetricsRecorder(
                meterRegistry, experiment);
        recorders.put(experiment.name(), recorder);
        log.info("Registered experiment {} with {} variants (total weight {})",
                experiment.name(), experiment.variants().size(), experiment.totalWeight());
        return recorder;
    }

    /** Lookup — empty if no experiment with this name is registered. */
    public Optional<Experiment> find(String name) {
        return Optional.ofNullable(experiments.get(name));
    }

    /**
     * Same as {@link #find(String)} but throws when missing — the pipeline
     * uses this to fail fast on misconfiguration.
     */
    public Experiment require(String name) {
        Experiment exp = experiments.get(name);
        if (exp == null) {
            throw new IllegalStateException("Experiment not registered: " + name);
        }
        return exp;
    }

    /** Recorder for the named experiment. */
    public ExperimentMetricsRecorder recorder(String name) {
        ExperimentMetricsRecorder r = recorders.get(name);
        if (r == null) {
            throw new IllegalStateException("No recorder for experiment: " + name);
        }
        return r;
    }

    /** All registered experiments — unmodifiable snapshot. */
    public Map<String, Experiment> all() {
        return Collections.unmodifiableMap(experiments);
    }

    /** Drop everything — only for tests / admin tools. */
    public void clear() {
        experiments.clear();
        recorders.clear();
        log.warn("ExperimentRegistry cleared ({} entries)", experiments.size());
    }

    /** Helper to build a consistent tag set — single source of truth. */
    public static Tags tags(String experimentName, ExperimentVariant variant) {
        return Tags.of("experiment", experimentName, "variant", variant.id());
    }
}