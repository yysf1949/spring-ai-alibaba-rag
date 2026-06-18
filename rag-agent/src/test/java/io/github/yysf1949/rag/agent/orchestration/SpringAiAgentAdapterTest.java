package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiAgentAdapterTest {

    @Test
    void registersFunctionCallbackForEachTool() throws Exception {
        // 用真实 InMemoryToolRegistry + 一个手动注册的 ToolDescriptor
        var registry = new InMemoryToolRegistry();
        Method m = FakeBean.class.getMethod("run", FakeBean.In.class);
        var desc = new ToolDescriptor("fake_tool", "Fake tool", RiskLevel.L1_READ, true, false, null, new FakeBean(), m);
        // 手动塞进 registry（不走 scan）
        var field = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (Map<String, ToolDescriptor>) field.get(registry);
        map.put("fake_tool", desc);

        var adapter = new SpringAiAgentAdapter(registry);
        var callbacks = adapter.getFunctionCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("fake_tool");
    }

    @Test
    void throwsWhenToolMissing() {
        var registry = new InMemoryToolRegistry();
        var adapter = new SpringAiAgentAdapter(registry);
        assertThatThrownBy(() -> adapter.invoke("nope", "{}"))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void invokeReturnsJsonString() {
        var registry = new InMemoryToolRegistry();
        try {
            var field = InMemoryToolRegistry.class.getDeclaredField("descriptors");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Map<String, ToolDescriptor>) field.get(registry);
            Method m = FakeBean.class.getMethod("run", FakeBean.In.class);
            map.put("fake_tool", new ToolDescriptor("fake_tool", "Fake", RiskLevel.L1_READ, true, false, null, new FakeBean(), m));
        } catch (Exception e) { throw new RuntimeException(e); }

        var adapter = new SpringAiAgentAdapter(registry);
        String result = adapter.invoke("fake_tool", "{\"q\":\"hi\"}");
        assertThat(result).contains("ok:hi");
    }
}