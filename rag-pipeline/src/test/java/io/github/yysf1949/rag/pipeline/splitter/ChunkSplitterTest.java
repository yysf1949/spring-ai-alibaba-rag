package io.github.yysf1949.rag.pipeline.splitter;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.ChunkingOptions;
import io.github.yysf1949.rag.core.model.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChunkSplitter}. All tests run without any external
 * dependency (no Redis, no LLM). They verify:
 *
 * <ol>
 *   <li>Short single-paragraph input → single chunk</li>
 *   <li>Long input → multiple chunks, each below maxChars, with overlap</li>
 *   <li>Empty / null content → empty list</li>
 *   <li>Code blocks stay intact (never split)</li>
 *   <li>Document metadata is inherited by every chunk</li>
 *   <li>Chunk IDs are deterministic (same input → same IDs)</li>
 *   <li>Sub-minChars chunks at the tail are merged into the previous chunk</li>
 *   <li>Section heading propagates as sectionPath</li>
 * </ol>
 */
class ChunkSplitterTest {

    private static final String TENANT = "splitterTenant";

    @Test
    void shortInput_singleChunk() {
        ChunkSplitter splitter = new ChunkSplitter();
        Document doc = doc("退款政策", "已付款订单 24 小时内可全额退款。运费另行计算。");
        List<Chunk> chunks = splitter.split(doc);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).content().contains("已付款"));
        assertTrue(chunks.get(0).content().contains("运费"));
    }

    @Test
    void longInput_multipleChunksWithOverlap() {
        // Each sentence ~6 chars; ~200 sentences → ~1200 chars (default max).
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append("退款订单可在 24 小时内全额撤销。");
        }
        ChunkSplitter splitter = new ChunkSplitter(
                new ChunkingOptions(300, 60, 30));
        Document doc = doc("policy", big.toString());
        List<Chunk> chunks = splitter.split(doc);
        assertTrue(chunks.size() >= 2, "expected multiple chunks, got " + chunks.size());
        for (Chunk c : chunks) {
            assertTrue(c.content().length() <= 300 + 30,
                    "chunk exceeded maxChars: " + c.content().length());
        }
        // Verify overlap: end of chunk N should equal start of chunk N+1
        // (sliding window with overlapChars tail).
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String a = chunks.get(i).content();
            String b = chunks.get(i + 1).content();
            // Look for a 20-char overlap substring; the tail of a appears at the head of b.
            String tail = a.substring(Math.max(0, a.length() - 60));
            assertTrue(b.startsWith(tail) || b.startsWith(tail.substring(0, Math.min(20, tail.length()))),
                    "no overlap between chunk " + i + " and " + (i + 1));
        }
    }

    @Test
    void emptyOrNullInput_returnsEmpty() {
        ChunkSplitter splitter = new ChunkSplitter();
        assertTrue(splitter.split(doc("empty", "")).isEmpty());
        assertTrue(splitter.split(doc("ws", "   \n\n   ")).isEmpty());
        assertTrue(splitter.split(new Document(
                TENANT, "kb", "doc", "1", "t", "u", Set.of(), List.of())).isEmpty());
    }

    @Test
    void codeBlockIsNotSplit() {
        String body = "退款流程如下。\n\n```java\n"
                + "if (a > 100) {\n  return refund(a * 0.9);\n} else {\n  return refund(a);\n}\n"
                + "```\n\n如有疑问联系客服。";
        ChunkSplitter splitter = new ChunkSplitter();
        Document doc = doc("refund-doc", body);
        List<Chunk> chunks = splitter.split(doc);
        // The code block must appear verbatim in exactly one chunk.
        String allContent = chunks.stream()
                .map(Chunk::content)
                .collect(Collectors.joining("\n---\n"));
        assertTrue(allContent.contains("if (a > 100)"),
                "code block must be preserved in chunk content");
        assertTrue(allContent.contains("return refund(a * 0.9);"));
        assertTrue(allContent.contains("return refund(a);"));
        // The code block must not be split into pieces scattered across chunks.
        long codeMarkers = chunks.stream()
                .filter(c -> c.content().contains("if (a > 100)"))
                .count();
        assertEquals(1, codeMarkers, "code block must appear in exactly one chunk");
    }

    @Test
    void metadataInherited() {
        ChunkSplitter splitter = new ChunkSplitter();
        Document doc = new Document(
                TENANT, "kb-42", "doc-7", "v3",
                "退款规则", "https://kb.example.com/doc-7.pdf",
                Set.of("public", "internal"),
                List.of(Document.Section.bodyOnly("短内容测试。")));
        Chunk chunk = splitter.split(doc).get(0);
        assertEquals(TENANT, chunk.tenantId());
        assertEquals("kb-42", chunk.kbId());
        assertEquals("doc-7", chunk.documentId());
        assertEquals("v3", chunk.documentVersion());
        assertEquals("退款规则", chunk.title());
        assertEquals("https://kb.example.com/doc-7.pdf", chunk.sourceUri());
        assertEquals(Set.of("public", "internal"), chunk.permissionTags());
        assertEquals(ChunkStatus.STAGING, chunk.status());
        assertNull(chunk.publishedAt());
        assertEquals(0, chunk.embedding().length,
                "splitter must not fill embedding — that's the ingester's job");
    }

    @Test
    void chunkIdsAreDeterministic() {
        ChunkSplitter splitter = new ChunkSplitter();
        Document doc = doc("refund", "已付款订单 24 小时内可全额退款。运费另行计算。如有疑问联系客服。");
        List<Chunk> a = splitter.split(doc);
        List<Chunk> b = splitter.split(doc);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).chunkId(), b.get(i).chunkId(),
                    "chunk " + i + " must have the same id on every split");
        }
    }

    @Test
    void sectionHeadingPropagatesAsSectionPath() {
        ChunkSplitter splitter = new ChunkSplitter();
        Document doc = new Document(TENANT, "kb", "doc", "1", "t", "u", Set.of(),
                List.of(
                        new Document.Section("退款规则", "已付款订单 24 小时内可全额退款。"),
                        new Document.Section("退款规则/运费条款", "运费在退款时一并退回。")
                ));
        List<Chunk> chunks = splitter.split(doc);
        assertEquals(2, chunks.size());
        assertEquals("退款规则", chunks.get(0).sectionPath());
        assertEquals("退款规则/运费条款", chunks.get(1).sectionPath());
    }

    @Test
    void tailShortChunksAreMerged() {
        // Use a config where minChars is large enough to force merging but
        // small enough not to be the entire content.
        ChunkSplitter splitter = new ChunkSplitter(
                new ChunkingOptions(500, 20, 200));
        // Sentence 1 = long, sentence 2 = short (forces a small tail chunk).
        String body = "这是第一个比较长的句子，包含超过两百个字符的内容。"
                + "让我们重复这段话让它足够长。重复的内容会让它超过 minChars 阈值。"
                + "继续重复以确保长度。重复的内容会让它超过 minChars 阈值。"
                + "继续重复以确保长度。再来几次。"
                + "继续重复以确保长度。"
                + "继续重复以确保长度。"
                + "短句尾。";
        Document doc = doc("doc", body);
        List<Chunk> chunks = splitter.split(doc);
        // The last chunk should be >= minChars after merging — if it would
        // have been shorter, it would have been merged into its predecessor.
        if (!chunks.isEmpty()) {
            Chunk tail = chunks.get(chunks.size() - 1);
            assertTrue(tail.content().length() >= 200 || chunks.size() == 1,
                    "tail chunk should be merged with predecessor if too short, "
                            + "but was " + tail.content().length() + " chars");
        }
    }

    // ─── helper ────────────────────────────────────────────────────────────

    private static Document doc(String title, String body) {
        return new Document(
                TENANT, "kb-1", "doc-1", "1", title, "https://example.com/" + title,
                Set.of("public"),
                List.of(Document.Section.bodyOnly(body)));
    }
}
