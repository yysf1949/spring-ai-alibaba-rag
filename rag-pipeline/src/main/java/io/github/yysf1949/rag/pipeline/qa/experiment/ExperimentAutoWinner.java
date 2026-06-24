package io.github.yysf1949.rag.pipeline.qa.experiment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decides whether an experiment has produced a statistically-significant
 * winner and which variant should be promoted to 100% traffic.
 *
 * <h2>Decision rules</h2>
 * <ol>
 *   <li>If both arms have at least {@link #minSamplePerArm} outcomes, run
 *       {@link ExperimentSignificanceTester#test(long, long, long, long)}.</li>
 *   <li>If p-value &lt; {@link #pValueCutoff} and {@code minSampleOk},
 *       promote the variant with the higher positive rate.</li>
 *   <li>If p-value &ge; cutoff, or minSample is not reached, return
 *       {@link Decision#noWinner(String)} — keep the experiment running.</li>
 *   <li>If both variants are tied exactly (same rates), also return
 *       {@code noWinner} — chi-square cannot distinguish a tie from
 *       a real effect, and we refuse to "promote" by coin flip.</li>
 * </ol>
 *
 * <h2>Why this is pure</h2>
 * The class takes only a {@link ExperimentRegistry} and an experiment name;
 * no Spring, no clock, no filesystem. Side effects (logging only) are
 * confined to the {@code log} field so the class is trivial to unit-test
 * and to drive from the auto-docs generator.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentAutoWinner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentAutoWinner.class);

    private final double pValueCutoff;
    private final int minSamplePerArm;

    public ExperimentAutoWinner() {
        this(ExperimentSignificanceTester.DEFAULT_P_VALUE_CUTOFF,
             ExperimentSignificanceTester.DEFAULT_MIN_SAMPLE);
    }

    public ExperimentAutoWinner(double pValueCutoff, int minSamplePerArm) {
        if (pValueCutoff <= 0 || pValueCutoff >= 1) {
            throw new IllegalArgumentException("pValueCutoff must be in (0, 1): " + pValueCutoff);
        }
        if (minSamplePerArm < 1) {
            throw new IllegalArgumentException("minSamplePerArm must be >= 1: " + minSamplePerArm);
        }
        this.pValueCutoff = pValueCutoff;
        this.minSamplePerArm = minSamplePerArm;
    }

    /**
     * Decide a winner for {@code experimentName}.
     *
     * @param registry the registry to read counts from
     * @param experimentName name of the experiment to evaluate
     * @return a {@link Decision} — never null
     */
    public Decision decide(ExperimentRegistry registry, String experimentName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(experimentName, "experimentName");
        Experiment experiment = registry.require(experimentName);
        if (experiment.variants().size() < 2) {
            return Decision.noWinner(
                    "Experiment " + experimentName + " has only "
                            + experiment.variants().size() + " variant — nothing to compare.");
        }
        // Convention: first variant is the control, second is the treatment.
        // Multi-arm extensions (3+) would need Bonferroni correction; out of scope for R14.
        ExperimentVariant control = experiment.variants().get(0);
        ExperimentVariant treatment = experiment.variants().get(1);
        ExperimentMetricsRecorder recorder = registry.recorder(experimentName);

        long controlPos = recorder.positives(control);
        long controlNeg = recorder.negatives(control);
        long treatmentPos = recorder.positives(treatment);
        long treatmentNeg = recorder.negatives(treatment);

        ExperimentSignificanceTester.Result result = ExperimentSignificanceTester.test(
                controlPos, controlNeg, treatmentPos, treatmentNeg,
                pValueCutoff, minSamplePerArm);

        log.info("AutoWinner evaluate experiment={} control=[pos={},neg={}] treatment=[pos={},neg={}] "
                        + "p={} significant={} minSampleOk={}",
                experimentName, controlPos, controlNeg, treatmentPos, treatmentNeg,
                result.pValue(), result.significant(), result.minSampleOk());

        if (!result.significant()) {
            return Decision.noWinner(buildNote(experimentName, result));
        }
        if (result.treatmentRate() == result.controlRate()) {
            return Decision.noWinner(experimentName
                    + ": arms tied exactly at " + result.controlRate()
                    + " positive rate — refusing to coin-flip a winner.");
        }
        ExperimentVariant winner = result.treatmentRate() > result.controlRate()
                ? treatment : control;
        return Decision.winner(winner, result, experimentName);
    }

    private static String buildNote(String experimentName, ExperimentSignificanceTester.Result r) {
        if (!r.minSampleOk()) {
            return experimentName + ": " + r.note() + " — keep collecting data.";
        }
        return String.format(
                "%s: not significant yet (p=%.4f >= %.3f, lift=%.2fbp) — keep collecting data.",
                experimentName, r.pValue(),
                ExperimentSignificanceTester.DEFAULT_P_VALUE_CUTOFF, r.liftBasisPoints());
    }

    /**
     * Outcome of {@link #decide(ExperimentRegistry, String)}.
     *
     * @param hasWinner     true iff a variant was promoted
     * @param winnerVariant the winning variant (null when {@code !hasWinner})
     * @param significance  the chi-square test result (always populated, even on no-winner)
     * @param note          human-readable rationale — useful for the auto-docs report
     */
    public record Decision(
            boolean hasWinner,
            ExperimentVariant winnerVariant,
            ExperimentSignificanceTester.Result significance,
            String note) {

        public static Decision winner(ExperimentVariant v,
                                      ExperimentSignificanceTester.Result r,
                                      String experimentName) {
            return new Decision(true, v, r,
                    String.format("%s: %s wins (p=%.4f, lift=%.2fbp)",
                            experimentName, v.id(), r.pValue(), r.liftBasisPoints()));
        }

        public static Decision noWinner(String note) {
            return new Decision(false, null, null, note);
        }
    }
}
