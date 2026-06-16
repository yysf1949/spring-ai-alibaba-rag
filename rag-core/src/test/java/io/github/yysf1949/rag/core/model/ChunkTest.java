package io.github.yysf1949.rag.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTest {

    @Test
    void minimalConstructor_fillsDefaults() {
        Chunk c = new Chunk(
                null,                       // chunkId should be auto-generated
                "t1", "kb1", "doc1", "v1",
                "title", "section",
                "content",
                Set.of("tag"),
                ChunkStatus.STAGING,
                Instant.now(),
                "https://example.com/doc",
                new float[]{0.1f, 0.2f}
        );

        assertNotNull(c.chunkId());
        UUID.fromString(c.chunkId());  // validates UUID format
        assertEquals("t1", c.tenantId());
        assertEquals(2, c.embeddingDim());
    }

    @Test
    void rejectsBlankTenantId() {
        assertThrows(IllegalArgumentException.class, () -> new Chunk(
                "c1", "  ", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.STAGING, Instant.now(), "uri",
                new float[0]));
    }

    @Test
    void rejectsBlankKbId() {
        assertThrows(IllegalArgumentException.class, () -> new Chunk(
                "c1", "t1", "", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.STAGING, Instant.now(), "uri",
                new float[0]));
    }

    @Test
    void rejectsNullEmbedding() {
        assertThrows(IllegalArgumentException.class, () -> new Chunk(
                "c1", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.STAGING, Instant.now(), "uri",
                null));
    }

    @Test
    void permissionTags_areCopiedAndImmutable() {
        Set<String> tags = Set.of("a", "b");
        Chunk c = new Chunk(
                "c1", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", tags,
                ChunkStatus.ACTIVE, Instant.now(), "uri",
                new float[16]);
        assertEquals(tags, c.permissionTags());
        // mutate original — must not affect chunk
        Set<String> mutable = new java.util.HashSet<>(tags);
        mutable.add("c");
        assertEquals(2, c.permissionTags().size());
    }

    @Test
    void isVisibleAt_onlyTrueForActiveChunksPublishedBeforeNow() {
        Instant past = Instant.now().minusSeconds(60);
        Instant future = Instant.now().plusSeconds(60);

        Chunk activePast = new Chunk("c1", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.ACTIVE, past, "uri", new float[16]);
        assertTrue(activePast.isVisibleAt(Instant.now()));

        Chunk activeFuture = new Chunk("c2", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.ACTIVE, future, "uri", new float[16]);
        assertFalse(activeFuture.isVisibleAt(Instant.now()));

        Chunk staging = new Chunk("c3", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.STAGING, past, "uri", new float[16]);
        assertFalse(staging.isVisibleAt(Instant.now()));

        Chunk deprecated = new Chunk("c4", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.DEPRECATED, past, "uri", new float[16]);
        assertFalse(deprecated.isVisibleAt(Instant.now()));
    }

    @Test
    void isVisibleAt_returnsFalseForNullPublishedAt() {
        Chunk c = new Chunk("c1", "t1", "kb1", "doc1", "v1",
                "t", "s", "x", Set.of(),
                ChunkStatus.ACTIVE, null, "uri", new float[16]);
        assertFalse(c.isVisibleAt(Instant.now()));
    }
}