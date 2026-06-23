package io.github.yysf1949.rag.agent.memory;

import java.util.List;
import java.util.Map;

/**
 * Serialisable, storage-friendly representation of a Spring AI {@code Message}.
 *
 * <h2>Why a separate record</h2>
 * <p>Spring AI's {@code Message} hierarchy ({@link org.springframework.ai.chat.messages.UserMessage},
 * {@link org.springframework.ai.chat.messages.AssistantMessage},
 * {@link org.springframework.ai.chat.messages.SystemMessage},
 * {@link org.springframework.ai.chat.messages.ToolResponseMessage}) cannot be
 * Jackson-serialised cleanly because (a) some fields live on subclasses
 * ({@code AssistantMessage.toolCalls}, {@code ToolResponseMessage.responses})
 * and (b) {@code AbstractMessage.textContent} is package-private.</p>
 *
 * <p>This record captures <em>everything</em> the four concrete message types
 * need to round-trip and is consumed by
 * {@link MessageSerializer#toRecord} and {@link MessageSerializer#fromRecord}.
 * Concrete implementations of {@link org.springframework.ai.chat.memory.ChatMemoryRepository}
 * (in this package) just call those two helpers and persist the resulting JSON.</p>
 *
 * <h2>JSON shape</h2>
 * <pre>{@code
 * {
 *   "type": "USER|ASSISTANT|SYSTEM|TOOL",
 *   "content": "Hello",
 *   "metadata": {"key": "value"},
 *   "toolCalls": [{"id":"tc-1","type":"function","name":"kb_search","arguments":"{\"q\":\"x\"}"}, ...],
 *   "toolResponses": [{"id":"tc-1","name":"kb_search","responseData":"{...}"}, ...]
 * }
 * }</pre>
 *
 * <p>{@code toolCalls} is only populated for {@code ASSISTANT} messages; {@code toolResponses}
 * only for {@code TOOL} messages. All other types leave both null.</p>
 *
 * @param type           Spring AI message type name (uppercase). Stored as the enum's
 *                       {@link org.springframework.ai.chat.messages.MessageType#name() name()}.
 * @param content        Plain-text payload ({@code null} for {@code TOOL} responses whose
 *                       data is carried inside {@code toolResponses}).
 * @param metadata       Per-message metadata map; may be null when the original message had none.
 * @param toolCalls      Assistant-side tool-call decision list. Null for non-assistant messages.
 * @param toolResponses  Tool-side response list. Null for non-tool messages.
 */
public record MessageRecord(
        String type,
        String content,
        Map<String, Object> metadata,
        List<ToolCallRecord> toolCalls,
        List<ToolResponseRecord> toolResponses) {

    /**
     * Compact form of {@link org.springframework.ai.chat.messages.AssistantMessage.ToolCall}.
     * The {@code arguments} string is kept as a raw JSON blob so we don't have to parse
     * it twice (LLM-side parsing already produced it).
     */
    public record ToolCallRecord(String id, String type, String name, String arguments) {}

    /**
     * Compact form of {@link org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse}.
     */
    public record ToolResponseRecord(String id, String name, String responseData) {}
}