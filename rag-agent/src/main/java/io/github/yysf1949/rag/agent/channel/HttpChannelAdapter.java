package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.ChannelAdapter;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HTTP 渠道适配器 — 把现有 AgentController 的 JSON 格式封装为 AgentRequest。
 *
 * <h2>Phase 10 范围</h2>
 * <p>本 adapter 是占位实现, Phase 11 会让 AgentController 通过
 * {@code ChannelAdapterRegistry} 路由到本 adapter, 而不是直接构造 AgentRequest。
 * Phase 10 仅保证接口存在 + HTTP 解析逻辑正确。</p>
 */
@Component
public class HttpChannelAdapter implements ChannelAdapter {

    @Override
    public AgentChannel channel() { return AgentChannel.HTTP; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentRequest parse(Object raw, AgentIdentity identity) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(
                    "HTTP channel expects Map body, got: " + raw.getClass().getSimpleName());
        }
        Map<String, Object> body = (Map<String, Object>) raw;
        String toolName = (String) body.getOrDefault("toolName", "");
        Object payload = body.get("payload");
        String tokenStr = (String) body.get("idempotencyToken");
        IdempotencyKey idem = (tokenStr != null && !tokenStr.isBlank())
                ? IdempotencyKey.of(identity.tenantId(), identity.userId(),
                identity.sessionId(), toolName, tokenStr)
                : null;
        return AgentRequest.of(identity, toolName, payload, idem);
    }
}
