package io.github.yysf1949.rag.pipeline.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-tenant synonym / controlled-vocabulary table. The {@link RuleBasedQueryRewriter}
 * applies these on top of the raw user query — design spec §11.2.
 *
 * <p>Concrete schema: each {@link Synonym} maps a <b>canonical term</b>
 * (the form we want to embed — e.g. {@code 退款}) to a list of
 * <b>surface forms</b> (what users actually type — e.g. {@code 退钱},
 * {@code 退回}, {@code 退订}). When the query contains any surface form,
 * the rewriter appends the canonical term to the rewritten text (if not
 * already present).</p>
 *
 * <p>This is intentionally a {@code Map<String, List<String>>}-shaped
 * object — easy to load from a YAML / properties file at startup, or to
 * override per-tenant in code.</p>
 *
 * <p>Thread-safety: implementations MUST be thread-safe. The default
 * {@link #empty()} and {@link #of(Map)} factories return immutable
 * instances.</p>
 */
public interface SynonymTable {

    /** @return all canonical terms defined in this table. */
    Set<String> canonicals();

    /**
     * Look up the surface forms for a canonical term.
     *
     * @return unmodifiable list of surface forms (may be empty)
     */
    List<String> surfacesOf(String canonical);

    /**
     * Reverse lookup — given a surface form, return the canonical term
     * that owns it (first match wins). Used by the rewriter when scanning
     * the input query.
     *
     * @return canonical term, or {@code null} if no synonym entry matches
     */
    String canonicalOf(String surface);

    /** Empty table — convenient default for tenants without a custom vocabulary. */
    static SynonymTable empty() {
        return new MapBackedSynonymTable(Map.of());
    }

    /**
     * Build a table from a {@code canonical -> surfaces} map. The map's
     * values are copied and frozen; subsequent mutation of the caller-side
     * map is invisible to the table.
     */
    static SynonymTable of(Map<String, ? extends List<String>> raw) {
        Objects.requireNonNull(raw, "raw");
        Map<String, List<String>> frozen = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, ? extends List<String>> e : raw.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new MapBackedSynonymTable(frozen);
    }

    /** Convenience: one synonym entry. */
    static SynonymTable of(String canonical, String... surfaces) {
        return of(Map.of(canonical, List.of(surfaces)));
    }

    /**
     * Concrete in-memory implementation, immutable after construction.
     */
    final class MapBackedSynonymTable implements SynonymTable {
        private final Map<String, List<String>> forward;
        private final Map<String, String> reverse;

        public MapBackedSynonymTable(Map<String, List<String>> forward) {
            this.forward = Map.copyOf(forward);
            Map<String, String> rev = new HashMap<>(this.forward.size() * 2);
            for (Map.Entry<String, List<String>> e : this.forward.entrySet()) {
                String canonical = e.getKey();
                for (String s : e.getValue()) {
                    // First-write wins — duplicate surface forms are tolerated
                    // by binding to whichever canonical claims them first.
                    rev.putIfAbsent(s, canonical);
                }
            }
            this.reverse = Map.copyOf(rev);
        }

        @Override
        public Set<String> canonicals() {
            return forward.keySet();
        }

        @Override
        public List<String> surfacesOf(String canonical) {
            List<String> s = forward.get(canonical);
            return s == null ? List.of() : s;
        }

        @Override
        public String canonicalOf(String surface) {
            return reverse.get(surface);
        }
    }

    /**
     * Helper for tests / builders — produces a mutable accumulator you can
     * {@code .add(canonical, surfaces...)} into and then {@code .build()}.
     */
    static Builder builder() {
        return new Builder();
    }

    final class Builder {
        private final Map<String, List<String>> raw = new HashMap<>();

        public Builder add(String canonical, String... surfaces) {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(surfaces, "surfaces");
            raw.computeIfAbsent(canonical, k -> new ArrayList<>()).addAll(List.of(surfaces));
            return this;
        }

        public Builder addAll(Map<String, ? extends List<String>> more) {
            for (Map.Entry<String, ? extends List<String>> e : more.entrySet()) {
                add(e.getKey(), e.getValue().toArray(new String[0]));
            }
            return this;
        }

        public SynonymTable build() {
            if (raw.isEmpty()) {
                return SynonymTable.empty();
            }
            return SynonymTable.of(raw);
        }

        public Map<String, List<String>> raw() {
            return Collections.unmodifiableMap(raw);
        }
    }
}
