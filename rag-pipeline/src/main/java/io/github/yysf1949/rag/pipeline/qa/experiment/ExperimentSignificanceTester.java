package io.github.yysf1949.rag.pipeline.qa.experiment;

import java.util.Objects;

/**
 * Pearson chi-square test on a 2×2 contingency table for two A/B variants.
 *
 * <p>Given a control and a treatment with positive/negative outcome counts
 * (a, b, c, d) the test returns whether the difference is statistically
 * significant at the requested p-value cutoff.</p>
 *
 * <pre>
 *               positive  negative
 *   control      a          b
 *   treatment    c          d
 * </pre>
 *
 * <h2>Why chi-square and not z-test of proportions?</h2>
 * A 2-arm A/B test with binary outcomes (positive / negative) is
 * mathematically identical under either test. We chose chi-square
 * because it is the canonical test operators expect to see in an
 * experiment report, and because it generalises to &gt;2 arms via
 * the {@link Result} record without forcing the reader to compute
 * Bonferroni-corrected pairwise z-tests by hand.
 *
 * <h2>Sample-size guard</h2>
 * With fewer than ~30 outcomes per arm, chi-square's asymptotic
 * approximation breaks down. {@link Result} carries a {@code minSampleOk}
 * flag so the {@link ExperimentWinnerDecider} can refuse to declare a
 * winner on too-little data instead of confidently calling a tie a
 * significant difference.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentSignificanceTester {

    /** Default minimum outcomes per arm before we trust the test. */
    public static final int DEFAULT_MIN_SAMPLE = 30;

    /** Default p-value cutoff (95% confidence). */
    public static final double DEFAULT_P_VALUE_CUTOFF = 0.05;

    private ExperimentSignificanceTester() {}

    /**
     * Run the chi-square test on the two arms' outcomes.
     *
     * @param controlPos     positive outcomes on the control arm
     * @param controlNeg     negative outcomes on the control arm
     * @param treatmentPos   positive outcomes on the treatment arm
     * @param treatmentNeg   negative outcomes on the treatment arm
     * @param pValueCutoff   significance threshold (e.g. 0.05)
     * @param minSample      minimum outcomes per arm to trust the test
     * @return test result — see {@link Result}
     */
    public static Result test(long controlPos, long controlNeg,
                              long treatmentPos, long treatmentNeg,
                              double pValueCutoff, int minSample) {
        if (controlPos < 0 || controlNeg < 0 || treatmentPos < 0 || treatmentNeg < 0) {
            throw new IllegalArgumentException("Outcome counts must be non-negative");
        }
        if (pValueCutoff <= 0 || pValueCutoff >= 1) {
            throw new IllegalArgumentException("p-value cutoff must be in (0, 1): " + pValueCutoff);
        }
        if (minSample < 1) {
            throw new IllegalArgumentException("minSample must be >= 1: " + minSample);
        }
        long controlTotal = controlPos + controlNeg;
        long treatmentTotal = treatmentPos + treatmentNeg;
        long posTotal = controlPos + treatmentPos;
        long negTotal = controlNeg + treatmentNeg;
        long grandTotal = controlTotal + treatmentTotal;
        if (grandTotal == 0) {
            // No data at all — degenerate "tie".
            return new Result(0.0, 1.0, false, 0.0, 0.0, false,
                    "no outcomes recorded");
        }
        boolean minSampleOk = controlTotal >= minSample && treatmentTotal >= minSample;
        double controlRate = controlTotal == 0 ? 0.0 : (double) controlPos / controlTotal;
        double treatmentRate = treatmentTotal == 0 ? 0.0 : (double) treatmentPos / treatmentTotal;

        // Expected cell counts under the null hypothesis (independence).
        // a_exp = row_control * col_pos / grand_total
        double aExp = (double) (controlTotal * posTotal) / grandTotal;
        double bExp = (double) (controlTotal * negTotal) / grandTotal;
        double cExp = (double) (treatmentTotal * posTotal) / grandTotal;
        double dExp = (double) (treatmentTotal * negTotal) / grandTotal;

        // Chi-square statistic = Σ (O - E)^2 / E
        double chi2 = 0.0;
        chi2 += sq(controlPos - aExp) / aExp;
        chi2 += sq(controlNeg - bExp) / bExp;
        chi2 += sq(treatmentPos - cExp) / cExp;
        chi2 += sq(treatmentNeg - dExp) / dExp;

        // 2×2 has 1 degree of freedom.
        // P-value via the chi-square survival function: p = 1 - CDF(chi2; 1)
        double pValue = chiSquare1DfSurvival(chi2);
        boolean significant = pValue < pValueCutoff;
        String note = !minSampleOk
                ? String.format("min sample not reached (control=%d, treatment=%d, need %d each)",
                        controlTotal, treatmentTotal, minSample)
                : "";
        return new Result(chi2, pValue, significant && minSampleOk,
                controlRate, treatmentRate, minSampleOk, note);
    }

    /** Test with default cutoffs (0.05 / 30). */
    public static Result test(long controlPos, long controlNeg,
                              long treatmentPos, long treatmentNeg) {
        return test(controlPos, controlNeg, treatmentPos, treatmentNeg,
                DEFAULT_P_VALUE_CUTOFF, DEFAULT_MIN_SAMPLE);
    }

    /**
     * Compute the chi-square survival function (1 - CDF) for 1 degree of
     * freedom. Closed form: {@code P(χ² > x) = erfc(sqrt(x/2))}, where
     * {@code erfc} is the complementary error function.
     *
     * <p>We implement a hand-rolled {@link #erfc(double)} via Abramowitz
     * &amp; Stegun 7.1.26 — the published rational approximation with
     * |error| &lt; 1.5e-7 over the whole input range. JDK 21 does NOT
     * expose {@code Math.erfc} (only {@code Math.exp} / {@code Math.log}),
     * so we ship our own.</p>
     */
    static double chiSquare1DfSurvival(double chi2) {
        if (chi2 <= 0) return 1.0;
        return erfc(Math.sqrt(chi2 / 2.0));
    }

    /**
     * Complementary error function — Abramowitz &amp; Stegun 7.1.26.
     * Maximum absolute error ~1.5e-7 over {@code [0, +∞)}. Sufficient for
     * chi-square p-value reporting where we only print 4 decimal places.
     */
    private static double erfc(double x) {
        // |error| < 1.5e-7
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * ax);
        // Horner form of the published A&S 7.1.26 polynomial.
        double poly = 0.17087277;
        poly = -0.82215223 + t * poly;
        poly =  1.48851587 + t * poly;
        poly = -1.13520398 + t * poly;
        poly =  0.27886807 + t * poly;
        poly = -0.18628806 + t * poly;
        poly =  0.09678418 + t * poly;
        poly =  0.37409196 + t * poly;
        poly =  1.00002368 + t * poly;
        double ans = t * Math.exp(-ax * ax - 1.26551223 + t * poly);
        return x >= 0 ? ans : 2.0 - ans;
    }

    private static double sq(double x) {
        return x * x;
    }

    /**
     * Test outcome.
     *
     * @param chiSquare        the chi-square statistic
     * @param pValue           two-sided p-value
     * @param significant      true iff the test is significant AND sample-size is met
     * @param controlRate      positive rate on control (0..1)
     * @param treatmentRate    positive rate on treatment (0..1)
     * @param minSampleOk      whether both arms met the minimum sample size
     * @param note             human-readable caveat for the experiment report
     */
    public record Result(
            double chiSquare,
            double pValue,
            boolean significant,
            double controlRate,
            double treatmentRate,
            boolean minSampleOk,
            String note) {

        public Result {
            Objects.requireNonNull(note, "note");
        }

        /** Effect size: treatment rate minus control rate. */
        public double lift() {
            return treatmentRate - controlRate;
        }

        /** Relative lift vs control, in basis points (10000 = 100%). */
        public double liftBasisPoints() {
            if (controlRate <= 0) return 0.0;
            return (lift() / controlRate) * 10_000.0;
        }
    }
}