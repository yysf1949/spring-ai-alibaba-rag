package io.github.yysf1949.rag.pipeline.qa.experiment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders an {@link ExperimentAutoWinner.Decision} to a human-readable
 * Markdown report and writes it to {@code docs/experiments/} so reviewers
 * can audit what the auto-winner saw when it declared a winner.
 *
 * <h2>Filename contract</h2>
 * <pre>{@code  docs/experiments/YYYY-MM-DD-<experiment-name>.md}</pre>
 * The date is "today" at the call site (injected via {@code today} arg
 * for testability — no {@code LocalDate.now()} in the body).
 *
 * <h2>Idempotency</h2>
 * If the target file already exists, the generator writes a sibling
 * {@code -<n>.md} instead of overwriting — auto-reports must not erase
 * prior runs.
 *
 * <h2>Why filesystem-coupled?</h2>
 * The whole point of this class is to drop a Markdown artifact the
 * reviewer can read in git. Side-effect-free variants live in
 * {@link #render(Experiment, ExperimentMetricsRecorder, ExperimentAutoWinner.Decision, LocalDate)};
 * callers that don't want filesystem side effects call that and ship
 * the string into Slack / GitHub / etc.
 *
 * <p>Phase 39 / R14 + R16.</p>
 */
public final class ExperimentReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExperimentReportGenerator.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path outputDir;

    public ExperimentReportGenerator(Path outputDir) {
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
    }

    /**
     * Render the markdown body for the given decision. Pure function —
     * safe to call from tests and from the {@code docs/} commit hook.
     */
    public String render(Experiment experiment,
                         ExperimentMetricsRecorder recorder,
                         ExperimentAutoWinner.Decision decision,
                         LocalDate today) {
        Objects.requireNonNull(experiment, "experiment");
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(today, "today");
        String date = today.format(DATE_FMT);
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# Experiment Report: ").append(experiment.name()).append("\n\n");
        sb.append("- **Date**: ").append(date).append("\n");
        sb.append("- **Outcome**: ");
        if (decision.hasWinner()) {
            sb.append("WINNER — promote `").append(decision.winnerVariant().id())
              .append("` to 100% traffic\n");
        } else {
            sb.append("INCONCLUSIVE — keep collecting data\n");
        }
        sb.append("- **Decision note**: ").append(decision.note()).append("\n\n");

        if (decision.significance() != null) {
            ExperimentSignificanceTester.Result r = decision.significance();
            sb.append("## Statistical Test (Pearson chi-square, 1 d.f.)\n\n");
            sb.append(String.format(Locale.ROOT,
                    "- chi² = %.4f%n- p-value = %.4f%n- significant at α=0.05: %s%n"
                            + "- control positive rate = %.2f%%%n"
                            + "- treatment positive rate = %.2f%%%n"
                            + "- lift (treatment − control) = %.2fbp%n"
                            + "- min-sample guard met: %s%n%n",
                    r.chiSquare(), r.pValue(), r.significant(),
                    r.controlRate() * 100, r.treatmentRate() * 100,
                    r.liftBasisPoints(), r.minSampleOk()));
        }

        sb.append("## Arm Counts\n\n");
        sb.append("| variant | exposures | positives | negatives | positive rate |\n");
        sb.append("|---------|-----------|-----------|-----------|---------------|\n");
        for (ExperimentVariant v : experiment.variants()) {
            long exposures = recorder.exposures(v);
            long pos = recorder.positives(v);
            long neg = recorder.negatives(v);
            double rate = (pos + neg) == 0 ? 0.0 : (double) pos / (pos + neg);
            sb.append(String.format(Locale.ROOT,
                    "| %s | %d | %d | %d | %.2f%%%n",
                    v.id(), exposures, pos, neg, rate * 100));
        }
        sb.append('\n');
        sb.append("## Variants Declared\n\n");
        for (ExperimentVariant v : experiment.variants()) {
            sb.append("- `").append(v.id()).append("` (weight=")
              .append(v.weight()).append(")\n");
        }
        if (!experiment.properties().isEmpty()) {
            sb.append("\n## Properties\n\n");
            experiment.properties().forEach((k, v) ->
                    sb.append("- `").append(k).append("` = `").append(v).append("`\n"));
        }
        sb.append("\n_Generated by `ExperimentReportGenerator` — Phase 39 / R16._\n");
        return sb.toString();
    }

    /**
     * Render and write to {@code outputDir/YYYY-MM-DD-<name>.md}.
     *
     * @return the absolute path of the written file
     */
    public Path writeReport(Experiment experiment,
                            ExperimentMetricsRecorder recorder,
                            ExperimentAutoWinner.Decision decision,
                            LocalDate today) throws IOException {
        String body = render(experiment, recorder, decision, today);
        Files.createDirectories(outputDir);
        String date = today.format(DATE_FMT);
        String safeName = experiment.name().replaceAll("[^A-Za-z0-9_.-]", "_");
        Path target = outputDir.resolve(date + "-" + safeName + ".md");
        int suffix = 1;
        while (Files.exists(target)) {
            target = outputDir.resolve(date + "-" + safeName + "-" + suffix + ".md");
            suffix++;
        }
        Files.writeString(target, body, StandardCharsets.UTF_8);
        log.info("Wrote experiment report to {}", target);
        return target.toAbsolutePath();
    }
}
