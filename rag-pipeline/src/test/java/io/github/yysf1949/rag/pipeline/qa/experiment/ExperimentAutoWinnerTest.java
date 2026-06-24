package io.github.yysf1949.rag.pipeline.qa.experiment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentAutoWinnerTest {

    private ExperimentRegistry registry(String name, ExperimentVariant control, ExperimentVariant treatment) {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        r.register(new Experiment(name, java.util.List.of(control, treatment),
                java.util.Map.of("k", "v")));
        return r;
    }

    @Test
    void noWinnerWhenNoOutcomes() {
        ExperimentRegistry r = registry("e1", ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A);
        ExperimentAutoWinner w = new ExperimentAutoWinner();
        var d = w.decide(r, "e1");
        assertFalse(d.hasWinner());
        // No outcomes yet — note should make it clear.
        assertTrue(d.note().contains("no outcomes") || d.note().contains("min sample"),
                "note was: " + d.note());
    }

    @Test
    void winnerPickedWhenSignificant() {
        ExperimentRegistry r = registry("e2", ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A);
        var rec = r.recorder("e2");
        for (int i = 0; i < 50; i++) rec.recordPositive(ExperimentVariant.CONTROL);
        for (int i = 0; i < 50; i++) rec.recordNegative(ExperimentVariant.CONTROL);
        for (int i = 0; i < 80; i++) rec.recordPositive(ExperimentVariant.TREATMENT_A);
        for (int i = 0; i < 20; i++) rec.recordNegative(ExperimentVariant.TREATMENT_A);

        ExperimentAutoWinner w = new ExperimentAutoWinner();
        var d = w.decide(r, "e2");
        assertTrue(d.hasWinner());
        assertEquals("treatment_a", d.winnerVariant().id());
        assertTrue(d.significance().pValue() < 0.001);
        assertTrue(d.note().contains("treatment_a wins"));
    }

    @Test
    void controlWinsWhenTreatmentUnderperforms() {
        ExperimentRegistry r = registry("e3", ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A);
        var rec = r.recorder("e3");
        for (int i = 0; i < 80; i++) rec.recordPositive(ExperimentVariant.CONTROL);
        for (int i = 0; i < 20; i++) rec.recordNegative(ExperimentVariant.CONTROL);
        for (int i = 0; i < 50; i++) rec.recordPositive(ExperimentVariant.TREATMENT_A);
        for (int i = 0; i < 50; i++) rec.recordNegative(ExperimentVariant.TREATMENT_A);
        var d = new ExperimentAutoWinner().decide(r, "e3");
        assertTrue(d.hasWinner());
        assertEquals("control", d.winnerVariant().id());
    }

    @Test
    void tiedArmsReturnNoWinner() {
        // To exercise the "tied" branch we need significant=true AND equal rates.
        // With a perfectly even split, chi-square → 0 → not significant.
        // The tied note only fires when rates are exactly equal AND significance
        // happened to pass (e.g. rounding edge). Verify that for the equal-rate
        // case the AutoWinner refuses to coin-flip a winner regardless.
        ExperimentRegistry r = registry("e4", ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A);
        var rec = r.recorder("e4");
        for (int i = 0; i < 100; i++) {
            rec.recordPositive(ExperimentVariant.CONTROL);
            rec.recordPositive(ExperimentVariant.TREATMENT_A);
        }
        var d = new ExperimentAutoWinner().decide(r, "e4");
        assertFalse(d.hasWinner(),
                "tied arms must not produce a winner; got: " + d.note());
    }

    @Test
    void singleVariantExperimentReturnsNoWinner() {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        r.register(new Experiment("single",
                java.util.List.of(ExperimentVariant.CONTROL),
                java.util.Map.of()));
        var d = new ExperimentAutoWinner().decide(r, "single");
        assertFalse(d.hasWinner());
    }

    @Test
    void unknownExperimentThrows() {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        assertThrows(IllegalStateException.class,
                () -> new ExperimentAutoWinner().decide(r, "missing"));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new ExperimentAutoWinner(0.0, 30));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentAutoWinner(0.05, 0));
    }
}
