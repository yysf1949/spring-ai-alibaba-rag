package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.port.LlmAuditHook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolAuditBridgeTest {

    @Test
    void recordsToolCallOnSuccess() {
        LlmAuditHook hook = mock(LlmAuditHook.class);
        var bridge = new ToolAuditBridge(hook);
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new ToolInvocationContext(identity, "kb_search",
                "{\"q\":\"hi\"}", "{\"results\":[]}", 42L, "SUCCESS");

        bridge.record(ctx);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // We just verify the call happened with non-blank content
        verify(hook).onLlmCall(
                org.mockito.ArgumentMatchers.eq("t1"),
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("s1"),
                captor.capture(),
                org.mockito.ArgumentMatchers.contains("kb_search"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("kb_search"),
                org.mockito.ArgumentMatchers.contains("SUCCESS"),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("SUCCESS"));
        assertThat(captor.getValue()).isNotBlank();
    }

    @Test
    void masksSensitiveDataInAudit() {
        // Phase 13a M1: 身份证 / 手机号不应出现在 audit 中
        LlmAuditHook hook = mock(LlmAuditHook.class);
        var bridge = new ToolAuditBridge(hook);
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new ToolInvocationContext(identity, "kb_search",
                "{\"phone\":\"13812345678\"}",
                "{\"result\":\"ok\"}", 10L, "SUCCESS");

        bridge.record(ctx);

        // promptBody = tool=... request=... — 不能含完整手机号
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(hook).onLlmCall(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                bodyCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(bodyCaptor.getValue()).contains("138****5678");
        assertThat(bodyCaptor.getValue()).doesNotContain("13812345678");
    }

    @Test
    void traceIdAppendedToPromptBodyWhenSet() {
        // Phase 13a M4: 当 TraceContext 设置时, promptBody 末尾追加 traceId
        LlmAuditHook hook = mock(LlmAuditHook.class);
        var bridge = new ToolAuditBridge(hook);
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new ToolInvocationContext(identity, "kb_search",
                "{\"q\":\"hi\"}", "{}", 10L, "SUCCESS");

        try (TraceContext.Scope s = TraceContext.enter("trace-abc-123")) {
            bridge.record(ctx);
        }

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(hook).onLlmCall(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                bodyCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(bodyCaptor.getValue()).contains("traceId=trace-abc-123");
    }
}
