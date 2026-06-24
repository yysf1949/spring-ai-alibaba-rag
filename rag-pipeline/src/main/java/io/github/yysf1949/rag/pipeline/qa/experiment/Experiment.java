package io.github.yysf1949.rag.pipeline.qa.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a single named A/B experiment.
 *
 * <p>An experiment is identified by {@link #name()} (must be unique within
 * a JVM), declares one or more {@link ExperimentVariant}s with relative
 * weights, and ships an arbitrary {@code properties} map the pipeline can
 * read to alter per-arm behaviour.</p>
 *
 * <h2>Weight semantics</h2>
 * Weights are <b>relative</b>, not absolute percentages — they are
 * normalised at assignment time. {@code [50, 50]} splits 50/50;
 * {@code [1, 1, 1]} splits 33/33/33; {@code [2, 1]} splits 66/33.
 *
 * <h2>Property override pattern</h2>
 * Properties are advisory, not enforced. The pipeline is free to ignore
 * keys it does not understand. Convention: prefix experiment-owned keys
 * with {@code experiment.<name>.<key>} so they do not collide with
 * operator-set tunables. Example:
 *
 * <pre>{@code
 * new Experiment("rag-retrieval-topk-v2",
 *     List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
 *     Map.of("experiment.rag-retrieval-topk-v2.topK", 10))
 * }</pre>
 *
 * <p>Phase 39 / R14.</p>
 */
public record Experiment(
        String name,
        List<ExperimentVariant> variants,
        Map<String, Object> properties) {

    public Experiment {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Experiment name must not be blank");
        }
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Experiment " + name + " must declare at least one variant");
        }
        // Defensive copy + order-preserving dedup
        var seen = new java.util.HashSet<String>();
        for (ExperimentVariant v : variants) {
            if (!seen.add(v.id())) {
                throw new IllegalArgumentException(
                        "Experiment " + name + " declares variant " + v.id() + " more than once");
            }
        }
        variants = List.copyOf(variants);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** Total weight — convenience for normaliser. */
    public double totalWeight() {
        return variants.stream().mapToDouble(ExperimentVariant::weight).sum();
    }

    /** Defensive lookup — never returns null. */
    public Object property(String key, Object fallback) {
        return properties.getOrDefault(key, fallback);
    }

    /** Builder — fluent construction for tests / config adapters. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private final List<ExperimentVariant> variants = new java.util.ArrayList<>();
        private final Map<String, Object> properties = new LinkedHashMap<>();

        public Builder name(String n) { this.name = n; return this; }
        public Builder variant(ExperimentVariant v) { this.variants.add(v); return this; }
        public Builder variants(List<ExperimentVariant> vs) { this.variants.addAll(vs); return this; }
        public Builder property(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }
        public Experiment build() {
            return new Experiment(name, variants, properties);
        }
    }
}