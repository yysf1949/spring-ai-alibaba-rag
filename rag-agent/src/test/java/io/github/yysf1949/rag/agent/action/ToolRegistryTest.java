package io.github.yysf1949.rag.agent.action;

import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Component
    static class FakeTool {
        public record Input(String q) {}
        public record Output(String answer) {}

        @ToolSpec(name = "fake_search", description = "Fake search", riskLevel = RiskLevel.L1_READ)
        public Output search(Input input) {
            return new Output("found: " + input.q());
        }
    }

    @Component
    static class BadTool {
        public record Input(String x) {}

        @ToolSpec(name = "no_return", description = "Void return is not allowed")
        public void doNothing(Input input) { }
    }

    private InMemoryToolRegistry registry;

    @BeforeEach
    void setUp() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(FakeTool.class);
            ctx.refresh();
            registry = new InMemoryToolRegistry();
            registry.scanFromContext(ctx);
        }
    }

    @Test
    void scansToolAnnotatedMethod() throws Exception {
        assertThat(registry.listNames()).contains("fake_search");
        ToolDescriptor desc = registry.get("fake_search");
        assertThat(desc.riskLevel()).isEqualTo(RiskLevel.L1_READ);
        assertThat(desc.description()).contains("Fake search");
    }

    @Test
    void invokeRunsTheTool() throws Exception {
        ToolDescriptor desc = registry.get("fake_search");
        Object out = desc.invoke(new FakeTool.Input("hello"));
        assertThat(out).isInstanceOf(FakeTool.Output.class);
        assertThat(((FakeTool.Output) out).answer()).isEqualTo("found: hello");
    }

    @Test
    void missingToolThrows() {
        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void voidReturnToolFailsValidation() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(BadTool.class);
            ctx.refresh();
            var fresh = new InMemoryToolRegistry();
            assertThatThrownBy(() -> fresh.scanFromContext(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no_return");
        }
    }
}