package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpChannelAdapterTest {

    private final HttpChannelAdapter adapter = new HttpChannelAdapter();
    private final AgentIdentity identity = new AgentIdentity(
            "tenant-1", "user-1", "session-1", Set.of("user"));

    @Test
    void channelIsHttp() {
        assertThat(adapter.channel()).isEqualTo(AgentChannel.HTTP);
    }

    @Test
    void parsesStandardBody() {
        Map<String, Object> body = Map.of(
                "toolName", "kb_search",
                "payload", Map.of("query", "怎么退款", "tenantId", "tenant-1"),
                "idempotencyToken", "tok-1");
        AgentRequest req = adapter.parse(body, identity);
        assertThat(req.toolName()).isEqualTo("kb_search");
        assertThat(req.channel()).isEqualTo(AgentChannel.HTTP);
        assertThat(req.idempotencyKey()).isNotNull();
        assertThat(req.idempotencyKey().rawToken()).isEqualTo("tok-1");
    }

    @Test
    void parsesBodyWithoutIdempotencyToken() {
        Map<String, Object> body = Map.of(
                "toolName", "kb_search",
                "payload", Map.of("query", "退款"));
        AgentRequest req = adapter.parse(body, identity);
        assertThat(req.idempotencyKey()).isNull();
    }

    @Test
    void rejectsNonMapBody() {
        assertThatThrownBy(() -> adapter.parse("not a map", identity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
