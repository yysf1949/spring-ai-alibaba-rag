package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.UserIdentityPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户身份查询工具 — L1 只读。
 *
 * <h2>对齐文章"查询用户身份"</h2>
 * <p>Agent 客服的第一个动作往往是确认用户身份：查会员等级（决定折扣）、
 * 查收货地址（处理物流问题）、查基本信息（核对订单归属）。</p>
 *
 * <h2>隐私保护</h2>
 * <p>手机号、邮箱等 PII 字段由 {@code SensitiveDataMasker} 在 Agent 层脱敏，
 * 本工具返回原始值。</p>
 */
@Component
public class UserIdentityTool {

    private final UserIdentityPort repo;

    public UserIdentityTool(UserIdentityPort repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "query_user_info",
            description = "查询用户身份信息（会员等级、积分、收货地址）。只读工具，不修改任何数据。"
                    + "适用于：确认用户身份、查看收货地址、判断会员权益。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public UserInfoResponse queryUserInfo(UserInfoRequest req) {
        var profile = repo.findProfile(req.tenantId(), req.userId());
        var addresses = repo.findAddresses(req.tenantId(), req.userId());

        if (profile.isEmpty()) {
            return new UserInfoResponse(req.userId(), "UNKNOWN", "UNKNOWN", 0, addresses, "用户不存在");
        }
        var p = profile.get();
        return new UserInfoResponse(
                p.userId(), p.memberLevel(), p.nickname(), p.points(),
                addresses, null);
    }

    public record UserInfoRequest(String tenantId, String userId) {}

    public record UserInfoResponse(
            String userId,
            String memberLevel,
            String nickname,
            long points,
            List<UserIdentityPort.Address> addresses,
            String error
    ) {}
}
