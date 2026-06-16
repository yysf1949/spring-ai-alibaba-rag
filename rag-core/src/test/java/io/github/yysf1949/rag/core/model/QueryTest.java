package io.github.yysf1949.rag.core.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {

    @Test
    void ofFactory_setsDefaultTopK() {
        Query q = Query.of("t1", "u1", "s1", "hello", Set.of("tag"));
        assertEquals("t1", q.tenantId());
        assertEquals(20, q.topK());
        assertNull(q.kbVersion());
        assertEquals(Set.of("tag"), q.permissionTags());
    }

    @Test
    void rejectsBlankRawText() {
        assertThrows(IllegalArgumentException.class,
                () -> Query.of("t1", "u1", "s1", "  ", Set.of()));
    }

    @Test
    void rejectsNegativeOrZeroTopK_andFallsBackToDefault() {
        Query q = new Query("t1", "u1", "s1", "hi", Set.of(), 0, null);
        assertEquals(20, q.topK(), "topK<=0 should fall back to default 20");

        Query q2 = new Query("t1", "u1", "s1", "hi", Set.of(), -5, null);
        assertEquals(20, q2.topK());
    }

    @Test
    void acceptsExplicitTopK() {
        Query q = new Query("t1", "u1", "s1", "hi", Set.of(), 50, null);
        assertEquals(50, q.topK());
    }

    @Test
    void nullPermissionTags_isNormalizedToEmptySet() {
        Query q = new Query("t1", "u1", "s1", "hi", null, 10, null);
        assertNotNull(q.permissionTags());
        assertTrue(q.permissionTags().isEmpty());
    }

    @Test
    void kbVersion_isRoundTrippable() {
        KbVersion v = new KbVersion("t1", "kb1", 7);
        Query q = new Query("t1", "u1", "s1", "hi", Set.of(), 10, v);
        assertSame(v, q.kbVersion());
        assertEquals(7, q.kbVersion().version());
    }
}