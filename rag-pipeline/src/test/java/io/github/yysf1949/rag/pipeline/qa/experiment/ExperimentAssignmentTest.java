package io.github.yysf1949.rag.pipeline.qa.experiment;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deterministic bucketing: same (experiment, subject) ⇒ same
 * variant, distribution roughly matches weights, degenerate inputs handled.
 */
class ExperimentAssignmentTest {

    @Test
    void deterministicSameSubjectSameVariant() {
        Experiment exp = Experiment.builder()
                .name("rag-topk-v2")
                .variant(new ExperimentVariant("control", 1.0))
                .variant(new ExperimentVariant("treatment_a", 1.0))
                .build();
        for (int i = 0; i < 50; i++) {
            String user = "user-" + i;
            ExperimentVariant a = ExperimentAssignment.assign(exp, user);
            ExperimentVariant b = ExperimentAssignment.assign(exp, user);
            assertEquals(a, b);
        }
    }

    @Test
    void distributionRoughlyMatchesWeights() {
        Experiment exp = Experiment.builder()
                .name("rag-50-50")
                .variant(new ExperimentVariant("control", 1.0))
                .variant(new ExperimentVariant("treatment_a", 1.0))
                .build();
        Map<String, Integer> counts = new HashMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            ExperimentVariant v = ExperimentAssignment.assign(exp, "user-" + i);
            counts.merge(v.id(), 1, Integer::sum);
        }
        double controlShare = counts.get("control") / (double) n;
        assertTrue(controlShare >= 0.45 && controlShare <= 0.55,
                "control share out of band: " + controlShare);
    }

    @Test
    void distributionMatchesUnequalWeights() {
        Experiment exp = Experiment.builder()
                .name("rag-80-20")
                .variant(new ExperimentVariant("control", 4.0))
                .variant(new ExperimentVariant("treatment_a", 1.0))
                .build();
        Map<String, Integer> counts = new HashMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            ExperimentVariant v = ExperimentAssignment.assign(exp, "user-" + i);
            counts.merge(v.id(), 1, Integer::sum);
        }
        double controlShare = counts.get("control") / (double) n;
        assertTrue(controlShare >= 0.76 && controlShare <= 0.84,
                "control share out of band: " + controlShare);
    }

    @Test
    void singleVariantAlwaysWins() {
        Experiment exp = Experiment.builder()
                .name("single")
                .variant(ExperimentVariant.CONTROL)
                .build();
        for (int i = 0; i < 100; i++) {
            assertSame(ExperimentVariant.CONTROL,
                    ExperimentAssignment.assign(exp, "u-" + i));
        }
    }

    @Test
    void sameExperimentNameYieldsSameVariantAcrossInstances() {
        Experiment exp1 = Experiment.builder()
                .name("rag-topk-v2")
                .variant(new ExperimentVariant("control", 1.0))
                .variant(new ExperimentVariant("treatment_a", 1.0))
                .build();
        Experiment exp2 = Experiment.builder()
                .name("rag-topk-v2")
                .variant(new ExperimentVariant("control", 1.0))
                .variant(new ExperimentVariant("treatment_a", 1.0))
                .build();
        Set<ExperimentVariant> set = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            set.add(ExperimentAssignment.assign(exp1, "user-" + i));
            assertEquals(ExperimentAssignment.assign(exp1, "user-" + i),
                    ExperimentAssignment.assign(exp2, "user-" + i));
        }
        assertEquals(2, set.size());
    }

    @Test
    void rejectsInvalidVariantId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentVariant("has spaces", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentVariant("中文id", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentVariant("", 1.0));
    }

    @Test
    void rejectsNegativeWeight() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentVariant("control", -0.1));
    }

    @Test
    void bucketKeyIsStable() {
        assertEquals(ExperimentAssignment.bucketKey("e1", "u1"),
                ExperimentAssignment.bucketKey("e1", "u1"));
        assertNotEquals(ExperimentAssignment.bucketKey("e1", "u1"),
                ExperimentAssignment.bucketKey("e1", "u2"));
    }

    @Test
    void experimentRejectsBlankNameAndEmptyVariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new Experiment("", List.of(ExperimentVariant.CONTROL), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Experiment("x", List.of(), Map.of()));
    }
}
