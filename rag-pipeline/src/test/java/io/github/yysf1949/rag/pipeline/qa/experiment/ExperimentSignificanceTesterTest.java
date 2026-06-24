package io.github.yysf1949.rag.pipeline.qa.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies chi-square arithmetic against hand-computed examples + edge cases.
 */
class ExperimentSignificanceTesterTest {

    @Test
    void noOutcomesReturnsTie() {
        var r = ExperimentSignificanceTester.test(0, 0, 0, 0);
        assertFalse(r.significant());
        assertFalse(r.minSampleOk());
        assertTrue(r.note().contains("no outcomes"));
    }

    @Test
    void minSampleGuardBlocksDecisionWhenTooFewSamples() {
        // Both arms have 5 outcomes, well below default min 30.
        var r = ExperimentSignificanceTester.test(5, 0, 0, 5);
        assertFalse(r.minSampleOk());
        assertFalse(r.significant());
        assertTrue(r.note().contains("min sample not reached"));
    }

    @Test
    void identicalRatesReturnNoWinner() {
        var r = ExperimentSignificanceTester.test(50, 50, 50, 50);
        assertFalse(r.significant());
        assertEquals(0.5, r.controlRate(), 1e-9);
        assertEquals(0.5, r.treatmentRate(), 1e-9);
    }

    @Test
    void clearlyDifferentRatesAreSignificant() {
        // Control 50%, treatment 70% on 200 outcomes each — p < 0.001 in reality.
        var r = ExperimentSignificanceTester.test(100, 100, 140, 60);
        assertTrue(r.minSampleOk());
        assertTrue(r.significant());
        assertTrue(r.pValue() < 0.05);
        assertEquals(0.20, r.lift(), 0.001);
        assertTrue(r.liftBasisPoints() > 3000);
    }

    @Test
    void nullSafetyOnRecords() {
        assertThrows(IllegalArgumentException.class,
                () -> ExperimentSignificanceTester.test(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ExperimentSignificanceTester.test(0, 0, 0, 0, 0, 30));
        assertThrows(IllegalArgumentException.class,
                () -> ExperimentSignificanceTester.test(0, 0, 0, 0, 0.05, 0));
    }

    @Test
    void chiSquareClosedFormSurvivalMonotonic() {
        // For 1 d.f., survival function is strictly decreasing in x.
        double prev = ExperimentSignificanceTester.chiSquare1DfSurvival(0.0);
        for (double x : new double[]{0.5, 1.0, 2.0, 5.0, 10.0}) {
            double cur = ExperimentSignificanceTester.chiSquare1DfSurvival(x);
            assertTrue(cur <= prev + 1e-9,
                    "survival not monotonic: prev=" + prev + " cur=" + cur + " x=" + x);
            prev = cur;
        }
        // The critical value at p=0.05 for 1 d.f. is 3.841.
        assertEquals(0.05,
                ExperimentSignificanceTester.chiSquare1DfSurvival(3.841),
                0.005);
    }
}
