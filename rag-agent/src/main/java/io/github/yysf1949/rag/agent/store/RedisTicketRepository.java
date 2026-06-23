package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@Profile("redis")
public class RedisTicketRepository implements TicketRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisTicketRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public TicketRecord save(TicketRecord ticket) {
        try {
            String key = factory.key("ticket", ticket.ticketId());
            String json = factory.mapper().writeValueAsString(ticket);
            factory.jedis().set(key, json);
            return ticket;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save ticket", e);
        }
    }

    @Override
    public Optional<TicketRecord> findById(String id) {
        try {
            String key = factory.key("ticket", id);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, TicketRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find ticket", e);
        }
    }

    @Override
    public List<TicketRecord> findByTenant(String tenantId) {
        try {
            String prefix = factory.entityPrefix("ticket");
            Set<String> keys = factory.jedis().keys(prefix + "*");
            List<TicketRecord> result = new ArrayList<>();
            if (keys != null) {
                for (String key : keys) {
                    String json = factory.jedis().get(key);
                    if (json != null) {
                        TicketRecord ticket = factory.mapper().readValue(json, TicketRecord.class);
                        if (tenantId.equals(ticket.tenantId())) {
                            result.add(ticket);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to find tickets by tenant", e);
        }
    }
}
