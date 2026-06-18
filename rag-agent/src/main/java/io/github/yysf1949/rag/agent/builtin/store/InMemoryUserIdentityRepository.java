package io.github.yysf1949.rag.agent.builtin.store;

import io.github.yysf1949.rag.agent.builtin.port.UserIdentityPort;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户身份 InMemory 实现 — 演示/测试用。
 */
import org.springframework.stereotype.Component;

@Component
public class InMemoryUserIdentityRepository implements UserIdentityPort {

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, List<Address>> addresses = new ConcurrentHashMap<>();

    public void saveProfile(UserProfile profile) {
        profiles.put(key(profile.tenantId(), profile.userId()), profile);
    }

    public void saveAddresses(String tenantId, String userId, List<Address> addrs) {
        addresses.put(key(tenantId, userId), addrs);
    }

    @Override
    public Optional<UserProfile> findProfile(String tenantId, String userId) {
        return Optional.ofNullable(profiles.get(key(tenantId, userId)));
    }

    @Override
    public List<Address> findAddresses(String tenantId, String userId) {
        return addresses.getOrDefault(key(tenantId, userId), List.of());
    }

    private static String key(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }
}