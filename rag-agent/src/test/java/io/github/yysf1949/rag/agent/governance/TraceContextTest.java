package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 上下文测试 — 4 个用例对齐"thread-isolated + nested-scope + MDC"3 大要求。
 */
class TraceContextTest {

    @Test
    void enterSetsCurrentTraceId() {
        assertThat(TraceContext.current()).isNull();
        try (TraceContext.Scope s = TraceContext.enter("trace-A")) {
            assertThat(TraceContext.current()).isEqualTo("trace-A");
        }
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void nestedScopesRestorePrevious() {
        try (TraceContext.Scope outer = TraceContext.enter("outer")) {
            assertThat(TraceContext.current()).isEqualTo("outer");
            try (TraceContext.Scope inner = TraceContext.enter("inner")) {
                assertThat(TraceContext.current()).isEqualTo("inner");
            }
            assertThat(TraceContext.current()).isEqualTo("outer");
        }
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void scopeCloseIsIdempotent() {
        TraceContext.Scope s = TraceContext.enter("trace-X");
        s.close();
        s.close(); // 重复关闭不抛异常
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void doesNotLeakToOtherThread() throws InterruptedException {
        TraceContext.enter("main-trace");
        try {
            String[] otherThreadValue = new String[1];
            Thread t = new Thread(() -> otherThreadValue[0] = TraceContext.current());
            t.start();
            t.join();
            assertThat(otherThreadValue[0]).isNull(); // 子线程不继承
            assertThat(TraceContext.current()).isEqualTo("main-trace"); // 主线程保留
        } finally {
            TraceContext.clear();
        }
    }
}