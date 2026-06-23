package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisMemberProfileRepository implements MemberProfileRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisMemberProfileRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public Optional<MemberProfile> findByTenantAndUser(String tenantId, String userId) {
        try {
            String key = factory.key("member", tenantId, userId);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, MemberProfile.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find member profile", e);
        }
    }

    @Override
    public MemberProfile save(MemberProfile profile) {
        try {
            String key = factory.key("member", profile.tenantId(), profile.userId());
            String json = factory.mapper().writeValueAsString(profile);
            factory.jedis().set(key, json);
            return profile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save member profile", e);
        }
    }
}
