package io.github.yysf1949.rag.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import redis.clients.jedis.UnifiedJedis;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisChatMemoryStore}.
 *
 * <p>Real Redis isn't available in the build environment, so we mock
 * {@link UnifiedJedis} with Mockito. To keep the assertions meaningful we back
 * the mock with a thread-safe in-memory map so {@code get}/{@code set}/
 * {@code del}/{@code smembers}/{@code sadd}/{@code srem} actually round-trip.</p>
 */
class RedisChatMemoryStoreTest {

    private Map<String, String> kv;
    private Set<String> indexSet;
    private UnifiedJedis jedis;
    private RedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        kv = new ConcurrentHashMap<>();
        indexSet = ConcurrentHashMap.newKeySet();
        jedis = mock(UnifiedJedis.class);
        // Wire mock methods to the backing maps
        lenient().when(jedis.get(anyString())).thenAnswer(inv -> kv.get(inv.getArgument(0, String.class)));
        lenient().when(jedis.set(anyString(), anyString())).thenAnswer(inv -> {
            kv.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return "OK";
        });
        lenient().when(jedis.del(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0, String.class);
            return kv.remove(k) != null ? 1L : 0L;
        });
        lenient().when(jedis.smembers(anyString())).thenAnswer(inv -> new HashSet<>(indexSet));
        lenient().when(jedis.sadd(anyString(), anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0, String.class);
            String v = inv.getArgument(1, String.class);
            // We only model the index key here.
            if ("rag:chat-memory:index".equals(k)) {
                return indexSet.add(v) ? 1L : 0L;
            }
            return 0L;
        });
        lenient().when(jedis.srem(anyString(), anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0, String.class);
            String v = inv.getArgument(1, String.class);
            if ("rag:chat-memory:index".equals(k)) {
                return indexSet.remove(v) ? 1L : 0L;
            }
            return 0L;
        });

        store = new RedisChatMemoryStore(jedis);
    }

    @Test
    void saveAllThenFindRoundTrip() {
        List<Message> msgs = List.of(
                UserMessage.builder().text("hi").build(),
                new AssistantMessage("hello!"));

        store.saveAll("c-1", msgs);

        // Verify the conversation blob landed at the right key with the right JSON shape.
        String json = kv.get("rag:chat-memory:conv:c-1");
        assertThat(json).contains("\"type\":\"USER\"");
        assertThat(json).contains("\"type\":\"ASSISTANT\"");
        assertThat(json).contains("hi");
        assertThat(json).contains("hello!");

        // Round-trip back through the store.
        List<Message> back = store.findByConversationId("c-1");
        assertThat(back).hasSize(2);
        assertThat(back.get(0).getText()).isEqualTo("hi");
        assertThat(back.get(1).getText()).isEqualTo("hello!");
    }

    @Test
    void saveAllUpdatesIndexSet() {
        store.saveAll("c-a", List.of(UserMessage.builder().text("a").build()));
        store.saveAll("c-b", List.of(UserMessage.builder().text("b").build()));

        assertThat(store.findConversationIds()).containsExactlyInAnyOrder("c-a", "c-b");
        verify(jedis).sadd(eq("rag:chat-memory:index"), eq("c-a"));
        verify(jedis).sadd(eq("rag:chat-memory:index"), eq("c-b"));
    }

    @Test
    void deleteByConversationIdRemovesBlobAndIndexEntry() {
        store.saveAll("c-1", List.of(UserMessage.builder().text("x").build()));
        assertThat(kv).containsKey("rag:chat-memory:conv:c-1");
        assertThat(indexSet).contains("c-1");

        store.deleteByConversationId("c-1");

        assertThat(kv).doesNotContainKey("rag:chat-memory:conv:c-1");
        assertThat(indexSet).doesNotContain("c-1");
        assertThat(store.findConversationIds()).doesNotContain("c-1");
    }

    @Test
    void findByConversationIdReturnsEmptyForMissing() {
        assertThat(store.findByConversationId("never-saved")).isEmpty();
    }

    @Test
    void emptyListSaveClearsConversation() {
        store.saveAll("c-1", List.of(UserMessage.builder().text("a").build()));
        store.saveAll("c-1", List.of());

        assertThat(store.findByConversationId("c-1")).isEmpty();
        assertThat(store.findConversationIds()).doesNotContain("c-1");
    }

    @Test
    void toolCallsAndToolResponsesRoundTrip() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "tc-1", "function", "kb_search", "{\"q\":\"refund\"}");
        AssistantMessage withTc = new AssistantMessage(
                "",
                Map.of(),
                List.of(tc));
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                "tc-1", "kb_search", "{\"chunks\":[]}");
        ToolResponseMessage withTr = new ToolResponseMessage(
                List.of(tr), Map.of());

        store.saveAll("c-tools", List.of(withTc, withTr));

        List<Message> back = store.findByConversationId("c-tools");
        assertThat(back).hasSize(2);
        AssistantMessage backTc = (AssistantMessage) back.get(0);
        assertThat(backTc.hasToolCalls()).isTrue();
        assertThat(backTc.getToolCalls().get(0).arguments()).isEqualTo("{\"q\":\"refund\"}");

        ToolResponseMessage backTr = (ToolResponseMessage) back.get(1);
        assertThat(backTr.getResponses()).hasSize(1);
        assertThat(backTr.getResponses().get(0).responseData()).isEqualTo("{\"chunks\":[]}");
    }

    @Test
    void findConversationIdsReturnsEmptyWhenIndexMissing() {
        // Before any save, the index set is empty.
        assertThat(store.findConversationIds()).isEmpty();
    }
}