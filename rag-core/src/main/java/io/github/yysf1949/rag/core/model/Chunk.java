package io.github.yysf1949.rag.core.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A single retrieval unit — typically 200-800 tokens of structured text.
 *
 * <p>Design spec §4 + §13.4 — fields are immutable. {@code embedding} is
 * a primitive {@code float[]} (1024 dim for SiliconFlow BAAI/bge-m3).
 *
 * <p>{@code embeddingChannel} records which provider produced the vector
 * ({@link EmbeddingChannel#STUB_HASH} for the local hash stub,
 * {@link EmbeddingChannel#SILICONFLOW_BGE_M3} for the online provider).
 *
 * <p>Indexes over a chunk MUST filter on (tenantId, kbId, kbVersion, status=ACTIVE,
 * permissionTags). See {@link io.github.yysf1949.rag.core.port.VectorStore}.</p>
 */
public record Chunk(
        String chunkId,
        String tenantId,
        String kbId,
        String documentId,
        String documentVersion,
        String title,
        String sectionPath,
        String content,
        Set<String> permissionTags,
        ChunkStatus status,
        Instant publishedAt,
        String sourceUri,
        float[] embedding,
        EmbeddingChannel embeddingChannel
) {

    public Chunk {
        if (chunkId == null || chunkId.isBlank()) {
            chunkId = UUID.randomUUID().toString();
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null (use empty float[] for un-embedded)");
        }
        permissionTags = permissionTags == null ? Set.of() : Set.copyOf(permissionTags);
        if (status == null) {
            status = ChunkStatus.STAGING;
        }
        embeddingChannel = embeddingChannel == null ? EmbeddingChannel.STUB_HASH : embeddingChannel;
    }

    public int embeddingDim() {
        return embedding.length;
    }

    /**
     * True when this chunk passes the standard visibility filter:
     * {@code ACTIVE + publishedAt <= now}. Tenant / KB / permission filtering
     * is the caller's responsibility (lives in {@code VectorStore}).
     */
    public boolean isVisibleAt(Instant now) {
        return status == ChunkStatus.ACTIVE
                && publishedAt != null
                && !publishedAt.isAfter(now);
    }
}
