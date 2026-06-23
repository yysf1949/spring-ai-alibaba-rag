package io.github.yysf1949.rag.agent.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bi-directional adapter between Spring AI {@link Message} instances and
 * {@link MessageRecord} JSON-friendly records.
 *
 * <p>Why not Jackson-serialise Spring AI's {@code Message} directly?
 * <ul>
 *   <li>{@code AbstractMessage.textContent} is package-private — Jackson's
 *       default field-based access can't see it.</li>
 *   <li>{@code AssistantMessage.toolCalls} and {@code ToolResponseMessage.responses}
 *       live on subclasses and aren't reached by polymorphic serialisation of the
 *       interface.</li>
 *   <li>Spring AI's {@code InMemoryChatMemoryRepository} stores {@code List<Message>}
 *       in-process and never serialises — persistence is our problem, not theirs.</li>
 * </ul>
 *
 * <p>This class normalises the four concrete message types down to a single
 * {@link MessageRecord} shape, and reconstructs the concrete subclass on read.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Stateless. Safe to share a single instance across threads.</p>
 */
public final class MessageSerializer {

    /**
     * Convert a Spring AI {@link Message} into a storage-friendly {@link MessageRecord}.
     *
     * @param message non-null Spring AI message
     * @return populated record; never null
     * @throws IllegalArgumentException if the message's runtime type is not one of the
     *                                  four known subtypes
     */
    public MessageRecord toRecord(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        MessageType type = message.getMessageType();
        String content = message.getText(); // AbstractMessage.getText() — public
        Map<String, Object> metadata = message.getMetadata();
        List<MessageRecord.ToolCallRecord> toolCalls = null;
        List<MessageRecord.ToolResponseRecord> toolResponses = null;

        // Downcast to capture subclass-specific fields. Switch on runtime type
        // rather than MessageType because some impls (e.g. Test stubs) might
        // report the right type but be the wrong runtime class.
        if (message instanceof AssistantMessage am) {
            if (am.hasToolCalls()) {
                toolCalls = new ArrayList<>(am.getToolCalls().size());
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    toolCalls.add(new MessageRecord.ToolCallRecord(
                            tc.id(), tc.type(), tc.name(), tc.arguments()));
                }
            }
        } else if (message instanceof ToolResponseMessage trm) {
            List<ToolResponseMessage.ToolResponse> rs = trm.getResponses();
            toolResponses = new ArrayList<>(rs.size());
            for (ToolResponseMessage.ToolResponse r : rs) {
                toolResponses.add(new MessageRecord.ToolResponseRecord(
                        r.id(), r.name(), r.responseData()));
            }
            // ToolResponseMessage.getText() returns the first responseData (see AbstractMessage javadoc).
            // We keep `content` populated anyway because AbstractMessage.getText() does that — if a
            // future Spring AI version changes that, downstream callers still see a stable string.
        } else if (!(message instanceof UserMessage) && !(message instanceof SystemMessage)) {
            throw new IllegalArgumentException(
                    "Unsupported Message runtime type: " + message.getClass().getName());
        }
        return new MessageRecord(type.name(), content, metadata, toolCalls, toolResponses);
    }

    /**
     * Convert a list of {@link Message}s into a list of records.
     */
    public List<MessageRecord> toRecords(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        List<MessageRecord> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            out.add(toRecord(m));
        }
        return out;
    }

    /**
     * Reconstruct Spring AI {@link Message} instances from a list of records.
     *
     * @param records non-null list (may be empty)
     * @return concrete {@code Message} instances ready to be handed back to
     *         {@link org.springframework.ai.chat.memory.MessageWindowChatMemory}
     */
    public List<Message> fromRecords(List<MessageRecord> records) {
        if (records == null) {
            return List.of();
        }
        List<Message> out = new ArrayList<>(records.size());
        for (MessageRecord rec : records) {
            out.add(fromRecord(rec));
        }
        return out;
    }

    /**
     * Reconstruct a single Spring AI {@link Message} from a record.
     *
     * @param rec non-null record
     * @return concrete {@link Message} subclass instance
     * @throws IllegalArgumentException if {@code rec.type()} is unknown
     */
    public Message fromRecord(MessageRecord rec) {
        if (rec == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        MessageType type;
        try {
            type = MessageType.valueOf(rec.type());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown message type: " + rec.type(), ex);
        }
        Map<String, Object> metadata = rec.metadata();
        String content = rec.content();

        return switch (type) {
            case USER -> UserMessage.builder()
                    .text(content == null ? "" : content)
                    .metadata(metadata == null ? Map.of() : metadata)
                    .build();
            case SYSTEM -> SystemMessage.builder()
                    .text(content == null ? "" : content)
                    .metadata(metadata == null ? Map.of() : metadata)
                    .build();
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> calls = List.of();
                if (rec.toolCalls() != null && !rec.toolCalls().isEmpty()) {
                    List<AssistantMessage.ToolCall> built = new ArrayList<>(rec.toolCalls().size());
                    for (MessageRecord.ToolCallRecord tcr : rec.toolCalls()) {
                        built.add(new AssistantMessage.ToolCall(
                                tcr.id(), tcr.type(), tcr.name(), tcr.arguments()));
                    }
                    calls = built;
                }
                yield new AssistantMessage(
                        content == null ? "" : content,
                        metadata == null ? Map.of() : metadata,
                        calls);
            }
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> rs = List.of();
                if (rec.toolResponses() != null && !rec.toolResponses().isEmpty()) {
                    List<ToolResponseMessage.ToolResponse> built = new ArrayList<>(rec.toolResponses().size());
                    for (MessageRecord.ToolResponseRecord trr : rec.toolResponses()) {
                        built.add(new ToolResponseMessage.ToolResponse(
                                trr.id(), trr.name(), trr.responseData()));
                    }
                    rs = built;
                }
                yield new ToolResponseMessage(rs, metadata == null ? Map.of() : metadata);
            }
        };
    }
}