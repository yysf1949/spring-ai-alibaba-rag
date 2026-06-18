package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.UserIdentityPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisUserIdentityRepository implements UserIdentityPort {

    private final RedisStoreFactory factory;

    public RedisUserIdentityRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public Optional<UserProfile> findProfile(String tenantId, String userId) {
        try {
            String key = factory.key("user_profile", tenantId, userId);
            Map<String, String> hash = factory.jedis().hgetAll(key);
            if (hash == null || hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new UserProfile(
                    hash.getOrDefault("userId", userId),
                    hash.getOrDefault("tenantId", tenantId),
                    hash.get("nickname"),
                    hash.get("realName"),
                    hash.get("mobile"),
                    hash.get("email"),
                    hash.get("memberLevel"),
                    Long.parseLong(hash.getOrDefault("points", "0"))
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find user profile", e);
        }
    }

    @Override
    public List<Address> findAddresses(String tenantId, String userId) {
        try {
            String key = factory.key("user_addresses", tenantId, userId);
            String json = factory.jedis().get(key);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return factory.mapper().readValue(json,
                    factory.mapper().getTypeFactory().constructCollectionType(List.class, Address.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find user addresses", e);
        }
    }
}
