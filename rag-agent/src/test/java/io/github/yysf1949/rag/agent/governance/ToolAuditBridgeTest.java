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
}
