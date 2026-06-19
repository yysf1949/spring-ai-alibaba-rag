package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory 会员档案仓库 — key = tenantId + "|" + userId, value = profile。
 */
@Component
@Profile("default")
public class InMemoryMemberProfileRepository implements MemberProfileRepositoryPort {

    private final ConcurrentHashMap<String, MemberProfile> store = new ConcurrentHashMap<>();

    @Override
    public Optional<MemberProfile> findByTenantAndUser(String tenantId, String userId) {
        return Optional.ofNullable(store.get(key(tenantId, userId)));
    }

    @Override
    public MemberProfile save(MemberProfile profile) {
        store.put(key(profile.tenantId(), profile.userId()), profile);
        return profile;
    }

    private static String key(String tenantId, String userId) {
        return tenantId + "|" + userId;
    }
}