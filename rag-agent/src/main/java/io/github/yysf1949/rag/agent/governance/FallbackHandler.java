package io.github.yysf1949.rag.agent.governance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link FallbackStrategy} implementation — emits a metric per
 * category and logs the decision. Concrete recovery logic (provider
 * list lookup, retry budget, rule-based answer assembly, handoff
 * routing) is wired in later phases (Phase 37 handoff, Phase 38
 * multi-LLM router). This Phase 32 ships the contract and the
 * observability hooks; later phases fill in the recovery.
 */
@Component
public class FallbackHandler implements FallbackStrategy {

    private static final Logger log = LoggerFactory.getLogger(FallbackHandler.class);

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Autowired
    public FallbackHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public void switchProvider(String reason) {
        record("switch_provider", reason);
    }

    @Override
    public void fallbackToRule(String reason) {
        record("fallback_to_rule", reason);
    }

    @Override
    public void retry(String reason) {
        record("retry", reason);
    }

    @Override
    public void skip(String reason) {
        record("skip", reason);
    }

    @Override
    public void handoff(String reason) {
        record("handoff", reason);
    }

    private void record(String action, String reason) {
        log.warn("Fallback strategy: {} triggered. reason={}", action.toUpperCase(), reason);
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return; // tests / non-Spring contexts
        }
        counters.computeIfAbsent(action, a -> Counter.builder("rag.fallback.triggered")
                .description("Number of fallback strategy invocations by category")
                .tag("action", a)
                .register(registry)).increment();
    }
}
