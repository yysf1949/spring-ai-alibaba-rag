package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@Profile("h2")
public class H2MemberProfileRepository implements MemberProfileRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<MemberProfile> MAPPER = (rs, row) -> {
        String perksStr = rs.getString("perks");
        List<String> perks = (perksStr == null || perksStr.isBlank())
                ? Collections.emptyList()
                : Arrays.asList(perksStr.split(","));
        return new MemberProfile(
                rs.getString("user_id"),
                rs.getString("tenant_id"),
                rs.getString("tier"),
                rs.getLong("points_balance"),
                perks
        );
    };

    public H2MemberProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<MemberProfile> findByTenantAndUser(String tenantId, String userId) {
        return jdbc.query("SELECT * FROM agent_member_profile WHERE tenant_id = ? AND user_id = ?",
                MAPPER, tenantId, userId).stream().findFirst();
    }

    @Override
    public MemberProfile save(MemberProfile profile) {
        String perksStr = (profile.perks() == null || profile.perks().isEmpty())
                ? ""
                : String.join(",", profile.perks());
        jdbc.update("MERGE INTO agent_member_profile (user_id, tenant_id, tier, points_balance, perks) "
                        + "KEY(user_id) VALUES (?, ?, ?, ?, ?)",
                profile.userId(), profile.tenantId(), profile.tier(),
                profile.pointsBalance(), perksStr);
        return profile;
    }
}
