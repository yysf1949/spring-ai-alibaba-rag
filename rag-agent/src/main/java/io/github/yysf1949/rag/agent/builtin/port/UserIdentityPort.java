package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

/**
 * 用户身份查询 Port — 松耦合接口。
 *
 * <p>对齐文章"查询用户身份"：Agent 需要知道用户是谁（身份、会员等级、收货地址）
 * 才能判断权限和提供个性化服务。</p>
 */
public interface UserIdentityPort {

    Optional<UserProfile> findProfile(String tenantId, String userId);

    List<Address> findAddresses(String tenantId, String userId);

    record UserProfile(
            String userId,
            String tenantId,
            String nickname,
            String realName,
            String mobile,
            String email,
            String memberLevel,
            long points
    ) {}

    record Address(
            String addressId,
            String recipientName,
            String mobile,
            String province,
            String city,
            String district,
            String detail,
            boolean isDefault
    ) {}
}
