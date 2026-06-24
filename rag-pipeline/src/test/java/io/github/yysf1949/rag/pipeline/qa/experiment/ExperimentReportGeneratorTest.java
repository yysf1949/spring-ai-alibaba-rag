package io.github.yysf1949.rag.pipeline.qa.experiment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentReportGeneratorTest {

    @Test
    void rendersWinnerReport(@TempDir Path tmp) throws IOException {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        Experiment exp = new Experiment("rag-citation-mode-v1",
                List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                Map.of("k", "v"));
        ExperimentMetricsRecorder rec = r.register(exp);
        for (int i = 0; i < 100; i++) {
            rec.recordExposure(ExperimentVariant.CONTROL);
            if (i < 50) rec.recordPositive(ExperimentVariant.CONTROL);
            else rec.recordNegative(ExperimentVariant.CONTROL);
        }
        for (int i = 0; i < 100; i++) {
            rec.recordExposure(ExperimentVariant.TREATMENT_A);
            if (i < 80) rec.recordPositive(ExperimentVariant.TREATMENT_A);
            else rec.recordNegative(ExperimentVariant.TREATMENT_A);
        }
        ExperimentAutoWinner.Decision d = new ExperimentAutoWinner().decide(r, "rag-citation-mode-v1");
        assertTrue(d.hasWinner());

        Path report = new ExperimentReportGenerator(tmp)
                .writeReport(exp, rec, d, LocalDate.of(2026, 6, 23));
        String body = Files.readString(report);
        assertEquals("2026-06-23-rag-citation-mode-v1.md", report.getFileName().toString());
        assertTrue(body.contains("# Experiment Report: rag-citation-mode-v1"));
        assertTrue(body.contains("treatment_a"));
        assertTrue(body.contains("p-value"));
        assertTrue(body.contains("control"));
    }

    @Test
    void rendersInconclusiveReport(@TempDir Path tmp) throws IOException {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        Experiment exp = new Experiment("rag-topk-v2",
                List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                Map.of());
        ExperimentMetricsRecorder rec = r.register(exp);
        for (int i = 0; i < 5; i++) rec.recordExposure(ExperimentVariant.CONTROL);
        ExperimentAutoWinner.Decision d = new ExperimentAutoWinner().decide(r, "rag-topk-v2");
        assertTrue(!d.hasWinner());

        Path report = new ExperimentReportGenerator(tmp)
                .writeReport(exp, rec, d, LocalDate.of(2026, 6, 23));
        String body = Files.readString(report);
        assertTrue(body.contains("INCONCLUSIVE"));
        assertTrue(body.contains("no outcomes") || body.contains("min sample"),
                "expected inconclusive marker in body, got: " + body.substring(0, Math.min(500, body.length())));
    }

    @Test
    void duplicateReportsGetSuffix(@TempDir Path tmp) throws IOException {
        ExperimentRegistry r = new ExperimentRegistry(new SimpleMeterRegistry());
        Experiment exp = new Experiment("rag-x",
                List.of(ExperimentVariant.CONTROL, ExperimentVariant.TREATMENT_A),
                Map.of());
        ExperimentMetricsRecorder rec = r.register(exp);
        ExperimentAutoWinner.Decision d = ExperimentAutoWinner.Decision.noWinner("test");
        ExperimentReportGenerator gen = new ExperimentReportGenerator(tmp);
        Path a = gen.writeReport(exp, rec, d, LocalDate.of(2026, 6, 23));
        Path b = gen.writeReport(exp, rec, d, LocalDate.of(2026, 6, 23));
        Path c = gen.writeReport(exp, rec, d, LocalDate.of(2026, 6, 23));
        assertEquals("2026-06-23-rag-x.md", a.getFileName().toString());
        assertEquals("2026-06-23-rag-x-1.md", b.getFileName().toString());
        assertEquals("2026-06-23-rag-x-2.md", c.getFileName().toString());
    }
}
