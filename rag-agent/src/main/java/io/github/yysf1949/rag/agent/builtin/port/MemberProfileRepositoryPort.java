package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

/**
 * Phase 14: 会员档案 Port — InMemory mock (生产替换为 CRM / 会员中心)。
 */
public interface MemberProfileRepositoryPort {
    Optional<MemberProfile> findByTenantAndUser(String tenantId, String userId);
    MemberProfile save(MemberProfile profile);

    record MemberProfile(
            String userId,
            String tenantId,
            String tier,        // NORMAL / GOLD / PLATINUM
            long pointsBalance,
            List<String> perks  // 特权列表
    ) { }
}