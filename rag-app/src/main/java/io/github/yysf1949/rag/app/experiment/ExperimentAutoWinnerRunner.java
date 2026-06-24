package io.github.yysf1949.rag.app.experiment;

import io.github.yysf1949.rag.pipeline.qa.experiment.Experiment;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentAutoWinner;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentMetricsRecorder;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentRegistry;
import io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;

/**
 * Periodic auto-winner check + report generator. Phase 39 / R14 + R16.
 *
 * <h2>What it does</h2>
 * On every tick, walks the {@link ExperimentRegistry} and, for every
 * experiment with at least 2 variants, asks the {@link ExperimentAutoWinner}
 * to make a decision. If a winner is detected, writes a Markdown report to
 * {@code <rag.experiment.report-dir>/YYYY-MM-DD-<name>.md} (default
 * {@code docs/experiments/}, relative to the JVM cwd).
 *
 * <h2>Cadence</h2>
 * Every 5 minutes by default. Operators tune via
 * {@code rag.experiment.auto-winner.cron}. Off by default in tests
 * (gated by {@code rag.experiment.auto-winner.enabled}).
 *
 * <h2>Idempotency</h2>
 * {@link ExperimentReportGenerator} suffixes duplicates with {@code -<n>},
 * so back-to-back runs do not overwrite prior reports.
 */
@Component
@ConditionalOnProperty(value = "rag.experiment.auto-winner.enabled",
        havingValue = "true", matchIfMissing = true)
public class ExperimentAutoWinnerRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentAutoWinnerRunner.class);

    private final ExperimentRegistry registry;
    private final ExperimentAutoWinner autoWinner;
    private final ExperimentReportGenerator reportGenerator;
    private final Path reportDir;

    public ExperimentAutoWinnerRunner(ExperimentRegistry registry,
                                      ExperimentAutoWinner autoWinner,
                                      @Value("${rag.experiment.report-dir:docs/experiments}")
                                      String reportDirPath) {
        this.registry = registry;
        this.autoWinner = autoWinner;
        this.reportDir = Paths.get(reportDirPath);
        this.reportGenerator = new ExperimentReportGenerator(reportDir);
    }

    /**
     * Periodic check. Runs every 5 minutes by default — operators tune via
     * {@code rag.experiment.auto-winner.cron}. Errors are logged, never thrown.
     */
    @Scheduled(fixedDelayString = "${rag.experiment.auto-winner.fixed-delay-ms:300000}",
            initialDelayString = "${rag.experiment.auto-winner.initial-delay-ms:30000}")
    public void tick() {
        try {
            runOnce(LocalDate.now());
        } catch (RuntimeException e) {
            log.warn("ExperimentAutoWinnerRunner tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Single evaluation pass — visible for testing and for manual triggering.
     *
     * @return number of reports written on this pass
     */
    public int runOnce(LocalDate today) {
        int written = 0;
        Map<String, Experiment> all = registry.all();
        if (all.isEmpty()) {
            return 0;
        }
        for (Map.Entry<String, Experiment> e : all.entrySet()) {
            String name = e.getKey();
            Experiment exp = e.getValue();
            if (exp.variants().size() < 2) {
                continue;
            }
            ExperimentAutoWinner.Decision decision;
            try {
                decision = autoWinner.decide(registry, name);
            } catch (RuntimeException ex) {
                log.warn("Auto-winner failed for experiment {}: {}", name, ex.getMessage());
                continue;
            }
            if (!decision.hasWinner()) {
                log.debug("No winner yet for experiment {}: {}", name, decision.note());
                continue;
            }
            try {
                ExperimentMetricsRecorder recorder = registry.recorder(name);
                Path written_path = reportGenerator.writeReport(exp, recorder, decision, today);
                log.info("Auto-winner: experiment={} → variant={}; report={}",
                        name, decision.winnerVariant().id(), written_path);
                written++;
            } catch (java.io.IOException ioe) {
                log.warn("Failed to write report for experiment {}: {}", name, ioe.getMessage());
            }
        }
        return written;
    }
}
