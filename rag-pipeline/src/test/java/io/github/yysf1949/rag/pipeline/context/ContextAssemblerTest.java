package io.github.yysf1949.rag.pipeline.context;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Citation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link ContextAssembler} — design spec §13.10 +
 * §15.3. Pure unit tests, no Redis, no external services.
 */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    // ─── helpers ──────────────────────────────────────────────────────────

    private static Chunk chunk(String id, String section, String content) {
        return new Chunk(id, "tenant-A", "kb-refund", "doc-1", "1",
                "退款规则", section, content,
                Set.of(), ChunkStatus.ACTIVE, Instant.now(),
                "https://docs.example.com/refund", new float[0], null);
    }

    // ─── happy path ───────────────────────────────────────────────────────

    @Test
    void assemblesAllChunksWhenBudgetIsGenerous() {
        List<Chunk> chunks = List.of(
                chunk("c1", "运费条款", "运费退还规则"),
                chunk("c2", "质量条款", "质量问题全额退款"));
        ContextAssembler.AssembledPrompt out = assembler.assemble(
                chunks, "运费退吗？", 1000);

        assertEquals(2, out.citations().size());
        assertTrue(out.hasCitations());
        assertFalse(out.anyTruncated());
        // Both citations should carry the same chunkId/title/section as input.
        assertEquals("c1", out.citations().get(0).chunkId());
        assertEquals("运费条款", out.citations().get(0).sectionPath());
        assertEquals("退款规则", out.citations().get(0).title());
        assertEquals("https://docs.example.com/refund", out.citations().get(0).sourceUri());
    }

    @Test
    void markerNumbersAre1BasedAndSequential() {
        List<Chunk> chunks = List.of(
                chunk("c1", "a", "x"),
                chunk("c2", "b", "y"),
                chunk("c3", "c", "z"));
        var out = assembler.assemble(chunks, "Q?", 1000);
        assertTrue(out.fullPrompt().contains("[1] 《"));
        assertTrue(out.fullPrompt().contains("[2] 《"));
        assertTrue(out.fullPrompt().contains("[3] 《"));
        // No [0] and no [4] — strictly 1..N.
        assertFalse(out.fullPrompt().contains("[0]"));
        assertFalse(out.fullPrompt().contains("[4]"));
    }

    @Test
    void promptContainsQueryText() {
        var out = assembler.assemble(List.of(chunk("c1", "s", "x")),
                "用户付了运费但商品质量问题退款，运费退吗？", 1000);
        assertTrue(out.fullPrompt().contains("用户付了运费但商品质量问题退款，运费退吗？"),
                "prompt must echo the user query under 【问题】");
    }

    // ─── truncation behaviour (spec §13.10) ──────────────────────────────

    @Test
    void truncatesBodyWhenBudgetTight() {
        // Each header is ~40 chars ~= 20 tokens; body "x" * 200 ~= 100 tokens.
        // With budget = 50, only 1 chunk's metadata fits, body truncated.
        String bigBody = "x".repeat(200);
        List<Chunk> chunks = List.of(
                chunk("c1", "section-1", bigBody),
                chunk("c2", "section-2", bigBody),
                chunk("c3", "section-3", bigBody));
        var out = assembler.assemble(chunks, "Q?", 50);

        assertTrue(out.anyTruncated(), "tight budget must mark truncation");
        // First chunk header preserved.
        assertTrue(out.fullPrompt().contains("[1] 《"));
        // Truncation marker present.
        assertTrue(out.fullPrompt().contains("…[truncated]"));
        // Citation still records the first chunk so the LLM has something to cite.
        assertFalse(out.citations().isEmpty());
    }

    @Test
    void headersNeverTruncatedEvenWithTinyBudget() {
        // Budget so small that even ONE header is too big — chunk gets
        // dropped (the algorithm's last-resort branch).
        var out = assembler.assemble(
                List.of(chunk("c1", "运费条款", "退款规则第七条")),
                "Q?", 1);
        assertNotNull(out);
        assertFalse(out.hasCitations(), "with budget=1, no chunk fits");
    }

    @Test
    void eachChunkHeaderAlwaysPreservedIndependently() {
        // 5 chunks with 1-char bodies. Each header line is ~61 chars
        // (~31 tokens), so 5 headers alone ≈ 155 tokens. Budget = 160 leaves
        // 5 tokens of body room; bodies are essentially empty but each chunk
        // still gets a citation header preserved.
        String body = "y";
        List<Chunk> chunks = List.of(
                chunk("c1", "a", body),
                chunk("c2", "b", body),
                chunk("c3", "c", body),
                chunk("c4", "d", body),
                chunk("c5", "e", body));
        var out = assembler.assemble(chunks, "Q?", 160);

        // Every chunk gets a citation header as long as the header itself fits.
        for (int i = 1; i <= 5; i++) {
            assertTrue(out.fullPrompt().contains("[" + i + "] 《"),
                    "header [" + i + "] missing — was metadata truncated?");
        }
        // All 5 citations still recorded — the LLM can cite-by-marker.
        assertEquals(5, out.citations().size());
        // 1-char bodies fit; no truncation marker needed for any chunk.
        assertFalse(out.anyTruncated(), "1-char bodies fit within remaining budget");
    }

    @Test
    void chunksDroppedWhenEvenHeaderDoesNotFit() {
        // Budget so small that one header is too big — algorithm must drop
        // and stop. This documents the edge case where even metadata
        // preservation is impossible.
        String body = "y".repeat(50);
        List<Chunk> chunks = List.of(
                chunk("c1", "a", body),
                chunk("c2", "b", body),
                chunk("c3", "c", body));
        var out = assembler.assemble(chunks, "Q?", 20); // < one header (~31 tokens)

        // 0 chunks fit — the algorithm logs a warn and stops.
        assertEquals(0, out.citations().size());
        assertFalse(out.anyTruncated(), "with 0 chunks, nothing was truncated");
    }

    // ─── PII redaction (spec §15.3) ───────────────────────────────────────

    @Test
    void piiInChunkBodyIsRedactedBeforePrompt() {
        Chunk withId = chunk("c1", "KYC", "用户 13800138000 提交了身份证 11010119900101001X");
        var out = assembler.assemble(List.of(withId), "Q?", 1000);
        // Phone and ID must be replaced with the canonical tags.
        assertTrue(out.fullPrompt().contains("***PHONE-REDACTED***"),
                "phone must be redacted; prompt was:\n" + out.fullPrompt());
        assertTrue(out.fullPrompt().contains("***ID-REDACTED***"),
                "ID card must be redacted; prompt was:\n" + out.fullPrompt());
        // And the original raw values must NOT appear anywhere in the prompt.
        assertFalse(out.fullPrompt().contains("13800138000"),
                "raw phone leaked into prompt:\n" + out.fullPrompt());
        assertFalse(out.fullPrompt().contains("11010119900101001X"),
                "raw ID card leaked into prompt:\n" + out.fullPrompt());
    }

    // ─── empty / null handling ────────────────────────────────────────────

    @Test
    void emptyChunksListProducesEmptyCitations() {
        var out = assembler.assemble(List.of(), "Q?", 1000);
        assertNotNull(out);
        assertFalse(out.hasCitations());
        assertFalse(out.anyTruncated());
        // Prompt envelope still rendered (template overhead alone).
        assertTrue(out.fullPrompt().contains("【问题】"));
    }

    @Test
    void nullChunksListTreatedAsEmpty() {
        var out = assembler.assemble(null, "Q?", 1000);
        assertNotNull(out);
        assertFalse(out.hasCitations());
    }

    @Test
    void blankQueryThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(List.of(), "", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(List.of(), null, 1000));
    }

    @Test
    void nonPositiveBudgetThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(List.of(), "Q?", 0));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(List.of(), "Q?", -1));
    }

    // ─── dependency injection (swap redactor / template) ─────────────────

    @Test
    void injectedRedactorOverridesDefault() {
        SensitiveDataRedactor echo = text -> "<ECHO>" + text + "</ECHO>";
        ContextAssembler custom = new ContextAssembler(
                new ApproxCharTokenCounter(), new DefaultPromptTemplate(), echo);
        var out = custom.assemble(
                List.of(chunk("c1", "s", "raw content")), "Q?", 1000);
        // The echo redactor wraps the body; the prompt template wraps that
        // with the chunk header.
        assertTrue(out.fullPrompt().contains("<ECHO>raw content</ECHO>"),
                "injected redactor's wrapper must appear in the prompt");
    }

    @Test
    void injectedTokenCounterAffectsTruncationBoundary() {
        // A 1:1 counter (no approximation) — every char costs 1 token.
        TokenCounter oneToOne = text -> text == null ? 0 : text.length();
        ContextAssembler precise = new ContextAssembler(
                oneToOne, new DefaultPromptTemplate(), new DefaultSensitiveDataRedactor());
        // Budget 100 chars; header+body for 2 chunks must overflow.
        var out = precise.assemble(
                List.of(
                        chunk("c1", "a", "x".repeat(200)),
                        chunk("c2", "b", "y".repeat(200))),
                "Q?", 100);
        assertTrue(out.anyTruncated());
    }

    // ─── citation score defaulting ────────────────────────────────────────

    @Test
    void citationScoreIsOneByDefault() {
        // The ContextAssembler does not consume rerank scores (the RerankService
        // upstream is the source of truth); we surface 1.0 here and let the
        // QAService override if it wants.
        var out = assembler.assemble(List.of(chunk("c1", "s", "x")), "Q?", 1000);
        Citation c = out.citations().get(0);
        assertEquals(1.0, c.score());
    }
}
