package io.github.yysf1949.rag.core.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RewriteResultTest {

    @Test
    void identityFactory_passesRawTextThroughWithMaxScore() {
        RewriteService.RewriteResult r = RewriteService.RewriteResult.identity("你好");
        assertEquals("你好", r.rewritten());
        assertEquals(1.0, r.ruleScore(), 0.0001);
        assertFalse(r.usedLlm());
    }

    @Test
    void rejectsBlankRewritten() {
        assertThrows(IllegalArgumentException.class,
                () -> new RewriteService.RewriteResult("", 0.5, false));
    }

    @Test
    void rejectsOutOfRangeRuleScore() {
        assertThrows(IllegalArgumentException.class,
                () -> new RewriteService.RewriteResult("hi", -0.1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new RewriteService.RewriteResult("hi", 1.5, false));
    }

    @Test
    void acceptsBoundaryScores() {
        assertDoesNotThrow(() -> new RewriteService.RewriteResult("a", 0.0, true));
        assertDoesNotThrow(() -> new RewriteService.RewriteResult("a", 1.0, false));
    }
}