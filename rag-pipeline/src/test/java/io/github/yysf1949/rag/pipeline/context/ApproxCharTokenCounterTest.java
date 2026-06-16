package io.github.yysf1949.rag.pipeline.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApproxCharTokenCounterTest {

    private final TokenCounter counter = new ApproxCharTokenCounter();

    @Test
    void nullAndEmptyReturnZero() {
        assertEquals(0, counter.count(null));
        assertEquals(0, counter.count(""));
    }

    @Test
    void singleCharRoundsUpToOne() {
        assertEquals(1, counter.count("a"));
    }

    @Test
    void twoCharsIsOneToken() {
        assertEquals(1, counter.count("ab"));
    }

    @Test
    void oddCharCountRoundsUp() {
        // 3 chars -> ceil(3/2)=2
        assertEquals(2, counter.count("abc"));
    }

    @Test
    void chineseText() {
        // 4 chars -> ceil(4/2) = 2
        assertEquals(2, counter.count("退款运费"));
        // 5 chars -> ceil(5/2) = 3
        assertEquals(3, counter.count("退款运费规则"));
    }

    @Test
    void neverReturnsNegative() {
        // Should never happen, but guard the contract.
        assertEquals(0, counter.count(null));
    }
}
