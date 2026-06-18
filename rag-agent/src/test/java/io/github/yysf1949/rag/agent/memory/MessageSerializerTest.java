package io.github.yysf1949.rag.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the bidirectional {@link MessageSerializer}.
 *
 * <p>Each Spring AI concrete {@link Message} subclass must round-trip cleanly
 * through {@link MessageRecord}. Anything that does not survive the round-trip
 * would silently disappear when the conversation is re-loaded from a
 * persistent store.</p>
 */
class MessageSerializerTest {

    private final MessageSerializer serializer = new MessageSerializer();

    @Test
    void userMessageRoundTrip() {
        UserMessage original = UserMessage.builder()
                .text("Hello world")
                .metadata(Map.of("k", "v"))
                .build();

        Message round = serializer.fromRecord(serializer.toRecord(original));

        assertThat(round).isInstanceOf(UserMessage.class);
        assertThat(round.getText()).isEqualTo("Hello world");
        assertThat(round.getMetadata()).containsEntry("k", "v");
        assertThat(round.getMessageType().name()).isEqualTo("USER");
    }

    @Test
    void systemMessageRoundTrip() {
        SystemMessage original = new SystemMessage("You are a helpful assistant");

        Message round = serializer.fromRecord(serializer.toRecord(original));

        assertThat(round).isInstanceOf(SystemMessage.class);
        assertThat(round.getText()).isEqualTo("You are a helpful assistant");
        assertThat(round.getMessageType().name()).isEqualTo("SYSTEM");
    }

    @Test
    void assistantMessagePlainTextRoundTrip() {
        AssistantMessage original = new AssistantMessage("Refunds are issued within 7 days.");

        Message round = serializer.fromRecord(serializer.toRecord(original));

        assertThat(round).isInstanceOf(AssistantMessage.class);
        assertThat(round.getText()).isEqualTo("Refunds are issued within 7 days.");
        assertThat(round.getMessageType().name()).isEqualTo("ASSISTANT");
    }

    @Test
    void assistantMessageWithToolCallsRoundTrip() {
        AssistantMessage.ToolCall tc1 = new AssistantMessage.ToolCall(
                "tc-1", "function", "kb_search", "{\"q\":\"refund policy\"}");
        AssistantMessage.ToolCall tc2 = new AssistantMessage.ToolCall(
                "tc-2", "function", "get_order", "{\"orderId\":\"O-100\"}");
        AssistantMessage original = new AssistantMessage(
                "",
                Map.of("traceId", "abc-123"),
                List.of(tc1, tc2));

        MessageRecord rec = serializer.toRecord(original);
        assertThat(rec.toolCalls()).hasSize(2);
        assertThat(rec.toolCalls().get(0).id()).isEqualTo("tc-1");
        assertThat(rec.toolCalls().get(1).name()).isEqualTo("get_order");

        Message round = serializer.fromRecord(rec);
        assertThat(round).isInstanceOf(AssistantMessage.class);
        AssistantMessage roundAsm = (AssistantMessage) round;
        assertThat(roundAsm.hasToolCalls()).isTrue();
        assertThat(roundAsm.getToolCalls()).hasSize(2);
        assertThat(roundAsm.getToolCalls().get(0).id()).isEqualTo("tc-1");
        assertThat(roundAsm.getToolCalls().get(0).arguments())
                .isEqualTo("{\"q\":\"refund policy\"}");
        assertThat(roundAsm.getMetadata()).containsEntry("traceId", "abc-123");
    }

    @Test
    void toolResponseMessageRoundTrip() {
        ToolResponseMessage.ToolResponse resp = new ToolResponseMessage.ToolResponse(
                "tc-1", "kb_search", "{\"chunks\":[]}");
        ToolResponseMessage original = new ToolResponseMessage(
                List.of(resp),
                Map.of("latencyMs", 42));

        MessageRecord rec = serializer.toRecord(original);
        assertThat(rec.toolResponses()).hasSize(1);
        assertThat(rec.toolResponses().get(0).name()).isEqualTo("kb_search");
        assertThat(rec.type()).isEqualTo("TOOL");

        Message round = serializer.fromRecord(rec);
        assertThat(round).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage roundTrm = (ToolResponseMessage) round;
        assertThat(roundTrm.getResponses()).hasSize(1);
        assertThat(roundTrm.getResponses().get(0).responseData()).isEqualTo("{\"chunks\":[]}");
        assertThat(round.getMetadata()).containsEntry("latencyMs", 42);
    }

    @Test
    void emptyTextIsPreserved() {
        // Spring AI's UserMessage builder auto-injects {"messageType":"USER"} into
        // metadata, so we can't test "null metadata" exactly. Instead verify that
        // a message whose text is the empty string round-trips cleanly without
        // throwing NPE.
        UserMessage original = UserMessage.builder().text("").build();
        assertThat(original.getText()).isEmpty();

        Message round = serializer.fromRecord(serializer.toRecord(original));

        assertThat(round.getText()).isEmpty();
        assertThat(round.getMessageType().name()).isEqualTo("USER");
    }

    @Test
    void unsupportedRuntimeTypeRejected() {
        // Build a fake Message that isn't one of the four subclasses.
        Message bogus = new Message() {
            @Override
            public org.springframework.ai.chat.messages.MessageType getMessageType() {
                return org.springframework.ai.chat.messages.MessageType.USER;
            }
            @Override
            public String getText() {
                return "bogus";
            }
            @Override
            public Map<String, Object> getMetadata() {
                return Map.of();
            }
        };

        assertThatThrownBy(() -> serializer.toRecord(bogus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Message runtime type");
    }

    @Test
    void toRecordsWithNullInputReturnsEmptyList() {
        assertThat(serializer.toRecords(null)).isEmpty();
        assertThat(serializer.fromRecords(null)).isEmpty();
    }
}