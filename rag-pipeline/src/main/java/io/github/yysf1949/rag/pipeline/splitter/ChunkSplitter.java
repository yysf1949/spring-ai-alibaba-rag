package io.github.yysf1949.rag.pipeline.splitter;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.ChunkingOptions;
import io.github.yysf1949.rag.core.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a {@link Document} into a list of {@link Chunk}s — design spec
 * §6.2 + §10.4.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>For each {@link Document.Section}, segment the body with
 *       {@link SentenceSegmenter} (preserves code blocks intact).</li>
 *   <li>Greedily accumulate segments into a buffer until adding the next
 *       segment would exceed {@link ChunkingOptions#maxChars()}.</li>
 *   <li>When the buffer overflows, emit it as a chunk. The next chunk's
 *       buffer is seeded with the trailing {@link ChunkingOptions#overlapChars()}
 *       characters of the previous emit (sliding window).</li>
 *   <li>After emission, chunks shorter than {@link ChunkingOptions#minChars()}
 *       are merged into the next chunk; if no next chunk exists (end of
 *       document) we leave them as-is — the alternative (merging backwards)
 *       would break the sliding-window overlap invariant.</li>
 *   <li>All emitted chunks inherit the document's tenantId / kbId /
 *       documentId / documentVersion / title / sourceUri / permissionTags,
 *       and get {@code sectionPath} from the section's heading path.</li>
 *   <li>{@code status=STAGING} and {@code publishedAt=null} — the upstream
 *       ingester is responsible for filling {@code embedding} and flipping
 *       the status after a successful publish.</li>
 * </ol>
 *
 * <h2>Complexity</h2>
 * O(n) in document size, single linear pass. No external dependencies
 * (no tokenizer; the {@code ChunkingOptions} character thresholds are
 * the configurable knob).
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe to call from a thread pool.
 */
public final class ChunkSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChunkSplitter.class);

    private final ChunkingOptions options;

    public ChunkSplitter() {
        this(ChunkingOptions.defaults());
    }

    public ChunkSplitter(ChunkingOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        this.options = options;
    }

    /**
     * @return list of chunks in document order. Never null; empty when the
     *         document has no content.
     */
    public List<Chunk> split(Document doc) {
        if (doc == null) {
            throw new IllegalArgumentException("doc must not be null");
        }
        List<Chunk> out = new ArrayList<>();
        if (doc.sections().isEmpty()) {
            return out;
        }
        // Document-level chunk counter — guarantees unique chunkIds across
        // sections. The previous design used a per-section counter that
        // reset to 0 each section, so the first chunk of every section
        // collided on the same deterministic chunkId seed (docId+sectionPath+0)
        // and overwrote each other in Redis. Cluster 7 真测 found that
        // 3-section documents only kept 1 chunk in the index.
        int[] globalIdx = {0};
        for (Document.Section section : doc.sections()) {
            splitSection(doc, section, out, globalIdx);
        }
        // Merge tail of the last section with the head (overlap seeding) — the
        // per-section loop already handles this internally; nothing extra here.
        log.debug("ChunkSplitter emitted {} chunks for doc={} sections={}",
                out.size(), doc.documentId(), doc.sections().size());
        return out;
    }

    public ChunkingOptions options() {
        return options;
    }

    // ─── internals ─────────────────────────────────────────────────────────

    private void splitSection(Document doc, Document.Section section, List<Chunk> sink, int[] globalIdx) {
        List<String> segments = SentenceSegmenter.split(section.body());
        if (segments.isEmpty()) {
            return;
        }
        String sectionPath = section.heading();
        int sectionChunkIndex = 0;
        // Per-section local sink so mergeShortTail can only touch chunks from
        // THIS section. Cross-section merging would silently rewrite the
        // sectionPath, which downstream consumers (ContextAssembler) rely on.
        List<Chunk> local = new ArrayList<>();

        for (String seg : segments) {
            // Long single segment (a code block that exceeds maxChars) — emit
            // as a standalone chunk, no merge, no overlap tail.
            if (seg.length() > options.maxChars()) {
                if (!local.isEmpty()) {
                    String emitted = concat(local);
                    local.clear();
                    local.add(buildChunk(doc, sectionPath, sectionChunkIndex++, emitted, globalIdx));
                }
                local.add(buildChunk(doc, sectionPath, sectionChunkIndex++, seg, globalIdx));
                continue;
            }

            if (local.isEmpty()) {
                local.add(buildChunk(doc, sectionPath, sectionChunkIndex++, seg, globalIdx));
                continue;
            }

            // Would adding this segment overflow? Emit the current buffer,
            // seed next chunk with the overlap tail.
            Chunk current = local.get(local.size() - 1);
            if (current.content().length() + seg.length() > options.maxChars()) {
                String emitted = current.content();
                String nextContent = overlapTail(emitted) + seg;
                if (nextContent.length() >= options.maxChars()) {
                    // Overlap tail alone is bigger than the segment — just
                    // start fresh with the segment (rare).
                    local.add(buildChunk(doc, sectionPath, sectionChunkIndex++, seg, globalIdx));
                } else {
                    local.add(buildChunk(doc, sectionPath, sectionChunkIndex++, nextContent, globalIdx));
                }
                continue;
            }

            // Append to the last chunk.
            String merged = current.content() + seg;
            local.set(local.size() - 1, buildChunk(doc, sectionPath, sectionChunkIndex - 1, merged, globalIdx));
        }

        // Section-local merge of sub-minChars trailing chunks.
        mergeShortTail(local);

        sink.addAll(local);
    }

    private String overlapTail(String text) {
        if (text.length() <= options.overlapChars()) {
            return text;
        }
        return text.substring(text.length() - options.overlapChars());
    }

    /**
     * Walk back from the end of {@code sink} and merge any trailing chunks
     * that are shorter than {@code minChars} into their predecessor, as
     * long as the merged length still fits within {@code maxChars}.
     */
    private void mergeShortTail(List<Chunk> sink) {
        while (sink.size() >= 2) {
            Chunk tail = sink.get(sink.size() - 1);
            if (tail.content().length() >= options.minChars()) {
                return;
            }
            Chunk prev = sink.get(sink.size() - 2);
            String merged = prev.content() + tail.content();
            if (merged.length() > options.maxChars()) {
                return; // can't merge without overflow; leave as-is
            }
            Chunk replaced = rebuildContent(prev, merged);
            sink.set(sink.size() - 2, replaced);
            sink.remove(sink.size() - 1);
        }
    }

    private void emit(Document doc, String sectionPath, int chunkIndex,
                      String content, List<Chunk> sink, int[] globalIdx) {
        sink.add(buildChunk(doc, sectionPath, chunkIndex, content, globalIdx));
    }

    private Chunk buildChunk(Document doc, String sectionPath, int chunkIndex, String content, int[] globalIdx) {
        // Use deterministic UUID seeded by (docId, sectionPath, globalChunkIndex)
        // so the same input always produces the same chunkId — useful for
        // idempotent re-ingest and for back-fill jobs. The global index
        // (counter across all sections in the doc) avoids collisions between
        // different sections that happen to share a section-local chunk index.
        String stableSeed = doc.documentId() + "|" + sectionPath + "|" + globalIdx[0]++;
        String chunkId = UUID.nameUUIDFromBytes(stableSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        return new Chunk(
                chunkId,
                doc.tenantId(),
                doc.kbId(),
                doc.documentId(),
                doc.documentVersion(),
                doc.title(),
                sectionPath,
                content,
                doc.permissionTags(),
                ChunkStatus.STAGING,
                null,            // publishedAt — flipped on publish
                doc.sourceUri(),
                new float[0],    // embedding — filled by the ingester
                null             // embeddingChannel — defaults to STUB_HASH
        );
    }

    /** Concatenate the content of every chunk in {@code chunks} (debug only). */
    private static String concat(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) sb.append(c.content());
        return sb.toString();
    }

    private static Chunk rebuildContent(Chunk original, String newContent) {
        return new Chunk(
                original.chunkId(),
                original.tenantId(),
                original.kbId(),
                original.documentId(),
                original.documentVersion(),
                original.title(),
                original.sectionPath(),
                newContent,
                original.permissionTags(),
                original.status(),
                original.publishedAt(),
                original.sourceUri(),
                original.embedding(),
                original.embeddingChannel()
        );
    }
}
