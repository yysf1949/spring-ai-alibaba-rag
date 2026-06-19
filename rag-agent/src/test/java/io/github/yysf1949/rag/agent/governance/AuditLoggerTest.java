package io.github.yysf1949.rag.agent.governance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggerTest {

    private MeterRegistry registry;
    private SensitiveDataMasker masker;
    private AuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        masker = new SensitiveDataMasker();
        auditLogger = new AuditLogger(registry, masker);
    }

    @Test
    void logGeneratesStructuredJsonLog() {
        // log() should not throw and counter should be incremented
        AuditEvent event = AuditEvent.of(
                "trace-1", "t1", "u1", "s1",
                "kb_search", "L1",
                "{\"q\":\"hello\"}", "{\"results\":[]}",
                "SUCCESS", 42L, null
        );

        auditLogger.log(event);

        Counter counter = registry.find("agent.audit.total")
                .tag("tool", "kb_search")
                .tag("outcome", "SUCCESS")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void logUpdatesMicrometerCounterByToolAndOutcome() {
        // Two different tools
        auditLogger.log(AuditEvent.of(
                "t1", "t", "u", "s", "kb_search", "L1", "{}", "{}", "SUCCESS", 10L, null));
        auditLogger.log(AuditEvent.of(
                "t2", "t", "u", "s", "refund", "L3", "{}", "{}", "FAILURE", 20L, "timeout"));
        auditLogger.log(AuditEvent.of(
                "t3", "t", "u", "s", "kb_search", "L1", "{}", "{}", "SUCCESS", 5L, null));

        assertThat(registry.find("agent.audit.total")
                .tag("tool", "kb_search").tag("outcome", "SUCCESS")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.find("agent.audit.total")
                .tag("tool", "refund").tag("outcome", "FAILURE")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void sensitiveFieldsAreMaskedInInputParams() {
        String inputWithPhone = "{\"phone\":\"13812345678\",\"name\":\"test\"}";
        String masked = masker.mask(inputWithPhone);

        AuditEvent event = AuditEvent.of(
                "trace-m", "t", "u", "s",
                "order_lookup", "L1",
                masked, "{}", "SUCCESS", 10L, null
        );

        auditLogger.log(event);

        // Verify the event inputParams are masked
        assertThat(event.inputParams()).contains("138****5678");
        assertThat(event.inputParams()).doesNotContain("13812345678");
    }

    @Test
    void errorOutcomeRecordsErrorMessage() {
        AuditEvent event = AuditEvent.of(
                "trace-err", "t", "u", "s",
                "payment", "L4",
                "{\"amount\":100}", null,
                "FAILURE", 500L, "Connection timeout to payment gateway"
        );

        auditLogger.log(event);

        assertThat(event.outcome()).isEqualTo("FAILURE");
        assertThat(event.errorMessage()).isEqualTo("Connection timeout to payment gateway");

        Counter counter = registry.find("agent.audit.total")
                .tag("tool", "payment")
                .tag("outcome", "FAILURE")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void logToolInvocationConvenienceMethod() {
        auditLogger.logToolInvocation("kb_search", "L1", "{\"q\":\"test\"}", "SUCCESS", 30L, null);

        Counter counter = registry.find("agent.audit.total")
                .tag("tool", "kb_search")
                .tag("outcome", "SUCCESS")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void logHandlesNullEventGracefully() {
        auditLogger.log(null);
        // Should not throw, no counters registered
        assertThat(registry.find("agent.audit.total").counter()).isNull();
    }

    @Test
    void auditEventFactoryGeneratesUniqueIds() {
        AuditEvent e1 = AuditEvent.of("t", "t", "u", "s", "tool", "L0", "{}", "{}", "SUCCESS", 0, null);
        AuditEvent e2 = AuditEvent.of("t", "t", "u", "s", "tool", "L0", "{}", "{}", "SUCCESS", 0, null);
        assertThat(e1.auditId()).isNotEqualTo(e2.auditId());
        assertThat(e1.timestamp()).isNotNull();
    }
}
