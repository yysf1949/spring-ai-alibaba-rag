package io.github.yysf1949.rag.redis.vector;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.EmbeddingChannel;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chunk ↔ Redis HASH field codec. Keeps the wire format identical to the
 * RediSearch schema declared in {@link RedisIndexManager#buildSchema()}.
 *
 * <p>Design spec §5.2 — embedding is stored as raw little-endian FLOAT32 bytes
 * (RediSearch expects the same when the schema declares {@code TYPE=FLOAT32}).
 * {@code permissionTags} are stored as a single {@code |}-separated string so
 * they fit the multi-value TAG field declared in the index.
 *
 * <p>This class is deliberately stateless; instances are cheap to share.</p>
 */
public final class RedisChunkCodec {

    /** Separator used by the RediSearch multi-value TAG field for permissionTags. */
    public static final String TAG_SEPARATOR = "|";

    private RedisChunkCodec() {
    }

    // ─── Chunk → Hash fields ───────────────────────────────────────────────

    /**
     * Encode a chunk into the HASH field map that {@code HSET} writes.
     * Embedding becomes a {@code byte[]} ready for the FLOAT32 vector column.
     */
    public static Map<String, String> toHashFields(Chunk chunk) {
        Map<String, String> m = new HashMap<>();
        m.put("chunkId", nz(chunk.chunkId()));
        m.put("tenantId", nz(chunk.tenantId()));
        m.put("kbId", nz(chunk.kbId()));
        m.put("documentId", nz(chunk.documentId()));
        m.put("documentVersion", nz(chunk.documentVersion()));
        m.put("status", chunk.status().name());
        m.put("publishedAt", String.valueOf(toEpochSeconds(chunk.publishedAt())));
        m.put("permissionTags", joinTags(chunk.permissionTags()));
        m.put("title", chunk.title() == null ? "" : chunk.title());
        m.put("sectionPath", chunk.sectionPath() == null ? "" : chunk.sectionPath());
        m.put("sourceUri", chunk.sourceUri() == null ? "" : chunk.sourceUri());
        m.put("content", chunk.content() == null ? "" : chunk.content());
        m.put("embeddingChannel", chunk.embeddingChannel().name());
        // embedding is stored as a separate field via HSET with binary value —
        // caller is responsible for adding it via the byte[] overload of hset.
        return m;
    }

    /** Encode the {@code embedding} column as little-endian FLOAT32 bytes. */
    public static byte[] toEmbeddingBytes(float[] embedding) {
        ByteBuffer bb = ByteBuffer.allocate(embedding.length * Float.BYTES);
        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(embedding);
        return bb.array();
    }

    // ─── HASH fields → Chunk ───────────────────────────────────────────────

    /**
     * Decode a RediSearch {@code Document} (with all scalar fields loaded +
     * embedding bytes passed in) back into a {@link Chunk}.
     */
    public static Chunk fromHashFields(String id, Map<String, String> fields, byte[] embeddingBlob) {
        String tenantId = fields.getOrDefault("tenantId", "");
        String kbId = fields.getOrDefault("kbId", "");
        String documentId = fields.getOrDefault("documentId", "");
        String documentVersion = fields.getOrDefault("documentVersion", "0");
        ChunkStatus status = parseStatus(fields.get("status"));
        Instant publishedAt = fields.containsKey("publishedAt")
                ? Instant.ofEpochSecond(Long.parseLong(fields.get("publishedAt")))
                : Instant.EPOCH;
        Set<String> permissionTags = splitTags(fields.getOrDefault("permissionTags", ""));

        float[] embedding = decodeEmbedding(embeddingBlob);

        return new Chunk(
                id,
                tenantId,
                kbId,
                documentId,
                documentVersion,
                fields.getOrDefault("title", ""),
                fields.getOrDefault("sectionPath", ""),
                fields.getOrDefault("content", ""),
                permissionTags,
                status,
                publishedAt,
                fields.getOrDefault("sourceUri", ""),
                embedding,
                parseEmbeddingChannel(fields.get("embeddingChannel"))
        );
    }

    private static float[] decodeEmbedding(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new float[0];
        }
        ByteBuffer bb = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static long toEpochSeconds(Instant i) {
        if (i == null) {
            return 0L;
        }
        return i.getEpochSecond();
    }

    static String joinTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String t : tags) {
            if (t == null || t.isEmpty()) continue;
            if (!first) sb.append(TAG_SEPARATOR);
            sb.append(t);
            first = false;
        }
        return sb.toString();
    }

    static Set<String> splitTags(String s) {
        if (s == null || s.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : s.split("\\|")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static ChunkStatus parseStatus(String s) {
        if (s == null || s.isEmpty()) {
            return ChunkStatus.STAGING;
        }
        try {
            return ChunkStatus.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return ChunkStatus.STAGING;
        }
    }

    /**
     * Parse {@code embeddingChannel} from Redis hash field.
     * Returns {@link EmbeddingChannel#STUB_HASH} for missing / unknown values
     * (backward compat with chunks stored before the field was added).
     */
    static EmbeddingChannel parseEmbeddingChannel(String s) {
        if (s == null || s.isEmpty()) {
            return EmbeddingChannel.STUB_HASH;
        }
        try {
            return EmbeddingChannel.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return EmbeddingChannel.STUB_HASH;
        }
    }

    /** Used by tests only. */
    static String utf8(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }
}
