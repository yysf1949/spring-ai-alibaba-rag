package io.github.yysf1949.rag.app.config;

import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.pipeline.qa.experiment.Experiment;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentAutoWinner;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentAware;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentRegistry;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentRegistryAware;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentVariant;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Spring wiring for the Phase 39 A/B experimentation framework.
 *
 * <h2>Wiring at a glance</h2>
 * <pre>{@code
 *   MeterRegistry ──> ExperimentRegistry ──> ExperimentRegistryAware ──> QAServiceImpl
 *                                 │                                          │
 *                                 └──> (one default Experiment registered     │
 *                                       at startup: rag-citation-mode-v1)     │
 *                                                                            │
 *   ExperimentAutoWinner ──> ExperimentRegistry (read-only)                   │
 * }</pre>
 *
 * <h2>Default experiment</h2>
 * Registers {@code rag-citation-mode-v1} at startup if no experiment is
 * registered. The experiment compares the current "inline [N] markers"
 * citation mode (control) against a candidate treatment; outcome
 * recording is driven by {@link io.github.yysf1949.rag.app.web.CitationFeedbackController}
 * (Phase 39 follow-up). Setting {@code rag.experiment.active-name=}
 * (blank) disables A/B entirely.
 *
 * <p>Phase 39 / R14.</p>
 */
@Configuration
public class ExperimentConfig {

    private static final Logger log = LoggerFactory.getLogger(ExperimentConfig.class);

    public static final String DEFAULT_EXPERIMENT = "rag-citation-mode-v1";

    @Bean
    public ExperimentRegistry experimentRegistry(MeterRegistry meterRegistry) {
        ExperimentRegistry registry = new ExperimentRegistry(meterRegistry);
        // Register a default 2-arm experiment — control vs treatment_a.
        // Weights are 50/50; operators can override via Experiment's
        // properties() map once Phase 39 follow-ups land a config file.
        Experiment exp = new Experiment(
                DEFAULT_EXPERIMENT,
                List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                Map.of(
                        "experiment." + DEFAULT_EXPERIMENT + ".controlDescription",
                        "inline [N] markers",
                        "experiment." + DEFAULT_EXPERIMENT + ".treatmentDescription",
                        "inline [N] markers + click-through links"
                ));
        registry.register(exp);
        return registry;
    }

    @Bean
    public ExperimentAware experimentAware(ExperimentRegistry registry) {
        return new ExperimentRegistryAware(registry);
    }

    @Bean
    public ExperimentAutoWinner experimentAutoWinner() {
        return new ExperimentAutoWinner();
    }

    /**
     * Wire the A/B hook into the {@link QAService} at startup.
     * The setter approach (vs adding another constructor arg) keeps the
     * pipeline module's master constructor signature stable so existing
     * rag-pipeline unit tests don't break.
     *
     * <p>We inject by the {@link QAService} interface (the bean is
     * declared with that type) and downcast to {@code QAServiceImpl}
     * to reach the A/B setter. If the production bean is ever replaced
     * with a non-{@code QAServiceImpl} implementation this method
     * silently does nothing — A/B is opt-in by implementation, not by
     * Spring type.</p>
     *
     * <p>Lives in its own {@code @Component} so Spring can resolve the
     * dependency on {@code experimentAware} (which is a bean declared
     * in {@link ExperimentConfig}) without a self-reference cycle.</p>
     */
    @org.springframework.stereotype.Component
    public static class ExperimentQaWiring {
        private static final Logger log = LoggerFactory.getLogger(ExperimentQaWiring.class);

        @Autowired
        public ExperimentQaWiring(QAService qaService,
                                  ExperimentAware experimentAware,
                                  @Value("${rag.experiment.active-name:" + DEFAULT_EXPERIMENT + "}")
                                  String activeExperimentName) {
            if (qaService instanceof io.github.yysf1949.rag.pipeline.qa.QAServiceImpl impl) {
                impl.setExperimentAware(experimentAware);
                impl.setActiveExperimentName(activeExperimentName);
                log.info("A/B experiment wired: active={}", activeExperimentName);
            } else {
                log.warn("QAService bean is {} — A/B experiment disabled (need QAServiceImpl)",
                        qaService == null ? "null" : qaService.getClass().getName());
            }
        }
    }

    /**
     * Hook for tests that want to disable A/B without removing the bean.
     * Lives here so {@link QAServiceImpl} stays Spring-free.
     */
    @PostConstruct
    public void logRegistry() {
        log.info("ExperimentConfig initialised; default experiment = {}", DEFAULT_EXPERIMENT);
    }
}
