package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.governance.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TraceIdFilter 测试 — 4 个用例对齐文章"可观测端到端串联"4 大行为。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void usesHeaderValueWhenPresent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER, "trace-from-header-123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader(TraceIdFilter.HEADER)).isEqualTo("trace-from-header-123");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // 不加 header
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 在 filter 链上注入一个观察者，验证 TraceContext 已被设置
        when(chain.toString()).thenReturn("mock-chain");

        filter.doFilter(req, resp, (request, response) -> {
            // 在 filter 内部 — TraceContext 应被填
            assertThat(TraceContext.current()).isNotNull();
            assertThat(TraceContext.current()).isEqualTo(resp.getHeader(TraceIdFilter.HEADER));
        });

        assertThat(resp.getHeader(TraceIdFilter.HEADER)).isNotNull();
        assertThat(TraceContext.current()).isNull(); // 退出 scope 后清理
    }

    @Test
    void clearsTraceContextAfterRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER, "trace-cleanup");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(TraceContext.current()).isNull();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcContainsTraceIdDuringRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER, "trace-mdc-789");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (request, response) ->
                assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("trace-mdc-789"));
    }
}