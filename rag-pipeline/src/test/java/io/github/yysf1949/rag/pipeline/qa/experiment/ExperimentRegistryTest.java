package io.github.yysf1949.rag.pipeline.qa.experiment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRegistryTest {

    @Test
    void registerAndRequire() {
        ExperimentRegistry registry = new ExperimentRegistry(new SimpleMeterRegistry());
        Experiment exp = new Experiment("e1",
                java.util.List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                java.util.Map.of());
        registry.register(exp);
        assertTrue(registry.find("e1").isPresent());
        assertEquals(exp, registry.require("e1"));
    }

    @Test
    void requireThrowsOnMissing() {
        ExperimentRegistry registry = new ExperimentRegistry(new SimpleMeterRegistry());
        assertThrows(IllegalStateException.class, () -> registry.require("missing"));
    }

    @Test
    void recorderCountersIncrementCorrectly() {
        ExperimentRegistry registry = new ExperimentRegistry(new SimpleMeterRegistry());
        Experiment exp = new Experiment("e2",
                java.util.List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                java.util.Map.of());
        ExperimentMetricsRecorder rec = registry.register(exp);
        for (int i = 0; i < 5; i++) rec.recordExposure(ExperimentVariant.CONTROL);
        for (int i = 0; i < 3; i++) rec.recordPositive(ExperimentVariant.CONTROL);
        for (int i = 0; i < 2; i++) rec.recordNegative(ExperimentVariant.CONTROL);
        assertEquals(5, rec.exposures(ExperimentVariant.CONTROL));
        assertEquals(3, rec.positives(ExperimentVariant.CONTROL));
        assertEquals(2, rec.negatives(ExperimentVariant.CONTROL));
        assertEquals(5, rec.totalOutcomes(ExperimentVariant.CONTROL));
    }

    @Test
    void allSnapshotIsUnmodifiable() {
        ExperimentRegistry registry = new ExperimentRegistry(new SimpleMeterRegistry());
        registry.register(new Experiment("e1",
                java.util.List.of(ExperimentVariant.CONTROL),
                java.util.Map.of()));
        assertEquals(1, registry.all().size());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.all().put("e2", null));
    }

    @Test
    void clearDropsEverything() {
        ExperimentRegistry registry = new ExperimentRegistry(new SimpleMeterRegistry());
        registry.register(new Experiment("e1",
                java.util.List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                java.util.Map.of()));
        registry.clear();
        assertEquals(0, registry.all().size());
        assertThrows(IllegalStateException.class, () -> registry.recorder("e1"));
    }

    @Test
    void tagsFormatIsStable() {
        assertEquals(2, ExperimentRegistry.tags("e1", ExperimentVariant.CONTROL).stream().count());
    }
}
