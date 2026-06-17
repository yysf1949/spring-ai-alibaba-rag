package io.github.yysf1949.rag.redis.vector;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java tests for the HASH codec — no Redis required.
 */
class RedisChunkCodecTest {

    @Test
    void roundtripEmbedding_bytesAreLittleEndianFloat32() {
        float[] vec = {1.0f, -2.5f, 3.14159f, 0f, Float.MAX_VALUE};
        byte[] blob = RedisChunkCodec.toEmbeddingBytes(vec);

        assertEquals(vec.length * Float.BYTES, blob.length, "byte size = 4 * float count");

        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        for (int i = 0; i < vec.length; i++) {
            assertEquals(vec[i], fb.get(), 0.0001f, "float[" + i + "] preserved");
        }
    }

    @Test
    void toHashFields_serializesAllScalarFields() {
        Instant now = Instant.parse("2026-06-16T12:00:00Z");
        Chunk c = new Chunk(
                "cid-1",
                "tenant-A",
                "kb-1",
                "doc-1",
                "7",
                "Hello",
                "/path",
                "Body text",
                Set.of("role:admin", "dept:eng"),
                ChunkStatus.ACTIVE,
                now,
                "https://example.com/doc-1",
                new float[]{0.1f, 0.2f},
                null
        );

        Map<String, String> fields = RedisChunkCodec.toHashFields(c);

        assertEquals("cid-1", fields.get("chunkId"));
        assertEquals("tenant-A", fields.get("tenantId"));
        assertEquals("kb-1", fields.get("kbId"));
        assertEquals("doc-1", fields.get("documentId"));
        assertEquals("7", fields.get("documentVersion"));
        assertEquals("ACTIVE", fields.get("status"));
        assertEquals("1781611200", fields.get("publishedAt"));
        // Set.of doesn't preserve order; verify the content rather than the joined form.
        String tagString = fields.get("permissionTags");
        assertTrue(tagString.contains("role:admin") && tagString.contains("dept:eng"),
                "permissionTags must contain both, got: " + tagString);
        assertEquals("Hello", fields.get("title"));
        assertEquals("Body text", fields.get("content"));
    }

    @Test
    void fromHashFields_roundTripPreservesEverything() {
        Instant now = Instant.parse("2026-06-16T12:00:00Z");
        Chunk original = new Chunk(
                "cid-2",
                "tenant-A",
                "kb-1",
                "doc-1",
                "7",
                "Hello",
                "/path",
                "Body text",
                Set.of("role:admin"),
                ChunkStatus.STAGING,
                now,
                "https://example.com/doc-1",
                new float[]{0.5f, 0.6f, 0.7f},
                null
        );
        Map<String, String> fields = RedisChunkCodec.toHashFields(original);
        byte[] embeddingBytes = RedisChunkCodec.toEmbeddingBytes(original.embedding());

        Chunk decoded = RedisChunkCodec.fromHashFields("cid-2", fields, embeddingBytes);

        assertEquals(original.chunkId(), decoded.chunkId());
        assertEquals(original.tenantId(), decoded.tenantId());
        assertEquals(original.kbId(), decoded.kbId());
        assertEquals(original.documentId(), decoded.documentId());
        assertEquals(original.documentVersion(), decoded.documentVersion());
        assertEquals(original.title(), decoded.title());
        assertEquals(original.content(), decoded.content());
        assertEquals(original.status(), decoded.status());
        assertEquals(original.publishedAt(), decoded.publishedAt());
        assertEquals(original.permissionTags(), decoded.permissionTags());
        assertArrayEquals(original.embedding(), decoded.embedding(), 0.0001f);
    }

    @Test
    void emptyAndNullPermissionTagsCollapseToEmptyString() {
        Chunk c1 = new Chunk("c", "t", "k", "d", "1", null, null, "x",
                null, ChunkStatus.STAGING, Instant.EPOCH, null, new float[0], null);
        Map<String, String> fields = RedisChunkCodec.toHashFields(c1);
        assertEquals("", fields.get("permissionTags"));

        Chunk decoded = RedisChunkCodec.fromHashFields("c", fields, new byte[0]);
        assertTrue(decoded.permissionTags().isEmpty());
    }

    @Test
    void tagSeparator_handlesPipeInValueSafely() {
        // Set.of() does not preserve order, so use List.of for the round-trip test.
        List<String> tags = List.of("a", "b", "c");
        String joined = RedisChunkCodec.joinTags(new java.util.LinkedHashSet<>(tags));
        assertEquals("a|b|c", joined);
        Set<String> back = RedisChunkCodec.splitTags(joined);
        assertEquals(Set.of("a", "b", "c"), back);
    }

    @Test
    void parseStatus_fallsBackToStagingOnGarbage() {
        assertEquals(ChunkStatus.STAGING, RedisChunkCodec.parseStatus(null));
        assertEquals(ChunkStatus.STAGING, RedisChunkCodec.parseStatus(""));
        assertEquals(ChunkStatus.STAGING, RedisChunkCodec.parseStatus("UNKNOWN"));
        assertEquals(ChunkStatus.ACTIVE, RedisChunkCodec.parseStatus("ACTIVE"));
        assertEquals(ChunkStatus.DEPRECATED, RedisChunkCodec.parseStatus("DEPRECATED"));
    }
}
