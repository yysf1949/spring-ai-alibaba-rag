package io.github.yysf1949.rag.pipeline.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynonymTableTest {

    @Test
    void emptyTableReturnsEmptyCanonicals() {
        SynonymTable t = SynonymTable.empty();
        assertTrue(t.canonicals().isEmpty());
        assertTrue(t.surfacesOf("anything").isEmpty());
        assertNull(t.canonicalOf("anything"));
    }

    @Test
    void reverseLookupMapsSurfaceToCanonical() {
        SynonymTable t = SynonymTable.of(Map.of(
                "退款", List.of("退钱", "退回"),
                "运费", List.of("邮费", "快递费")));
        assertEquals("退款", t.canonicalOf("退钱"));
        assertEquals("退款", t.canonicalOf("退回"));
        assertEquals("运费", t.canonicalOf("邮费"));
        assertEquals("运费", t.canonicalOf("快递费"));
    }

    @Test
    void duplicateSurfaceBindsToExactlyOneCanonical() {
        // Two canonicals both declare "退" in their forward entries — that's
        // allowed in the schema. The contract is on the REVERSE lookup:
        // canonicalOf("退") must return exactly one canonical (no ambiguity
        // at query time).
        SynonymTable t = SynonymTable.of(Map.of(
                "退款", List.of("退"),
                "退货", List.of("退")));
        String winner = t.canonicalOf("退");
        assertNotNull(winner, "reverse lookup must resolve to exactly one canonical");
        assertTrue(t.canonicals().contains(winner));
    }

    @Test
    void ofRejectsNullRaw() {
        assertThrows(NullPointerException.class, () -> SynonymTable.of(null));
    }

    @Test
    void builderAccumulatesAndBuilds() {
        SynonymTable t = SynonymTable.builder()
                .add("退款", "退钱", "退回")
                .add("运费", "邮费")
                .add("运费", "快递费")  // accumulates surfaces under same canonical
                .build();
        assertEquals(2, t.canonicals().size());
        // 退款 has 2 surfaces, 运费 has 2 surfaces → 4 total.
        assertEquals(2, t.surfacesOf("退款").size());
        assertEquals(2, t.surfacesOf("运费").size());
        assertTrue(t.surfacesOf("退款").containsAll(List.of("退钱", "退回")));
        assertTrue(t.surfacesOf("运费").containsAll(List.of("邮费", "快递费")));
    }

    @Test
    void builderAddAllMergesMap() {
        SynonymTable t = SynonymTable.builder()
                .addAll(Map.of("订单", List.of("单号")))
                .addAll(Map.of("物流", List.of("快递")))
                .build();
        assertEquals("订单", t.canonicalOf("单号"));
        assertEquals("物流", t.canonicalOf("快递"));
    }

    @Test
    void builderRejectsNullCanonicalOrSurfaces() {
        assertThrows(NullPointerException.class,
                () -> SynonymTable.builder().add(null, "x"));
        assertThrows(NullPointerException.class,
                () -> SynonymTable.builder().add("退款", (String[]) null));
    }

    @Test
    void emptyBuilderProducesEmptyTable() {
        assertTrue(SynonymTable.builder().build().canonicals().isEmpty());
    }

    @Test
    void rawReturnsUnmodifiableView() {
        SynonymTable t = SynonymTable.of("退款", "退钱");
        var raw = SynonymTable.builder().add("退款", "退钱").raw();
        assertThrows(UnsupportedOperationException.class, () -> raw.put("x", List.of()));
    }

    @Test
    void defaultChineseSynonymTableCoversArticleScenarios() {
        SynonymTable t = DefaultChineseSynonymTable.create();
        assertNotNull(t);
        // 退款 must have at least one surface (退钱 etc.)
        assertTrue(t.surfacesOf("退款").size() >= 3);
        assertEquals("退款", t.canonicalOf("退钱"));
        // 运费 should be there too.
        assertNotNull(t.canonicalOf("邮费"));
    }

    @Test
    void canonicalsReturnsImmutableSet() {
        SynonymTable t = SynonymTable.of("退款", "退钱");
        assertThrows(UnsupportedOperationException.class, () -> t.canonicals().add("X"));
    }
}
