package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.UserIdentityPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile("h2")
public class H2UserIdentityRepository implements UserIdentityPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<UserProfile> PROFILE_MAPPER = (rs, row) -> new UserProfile(
            rs.getString("user_id"),
            rs.getString("tenant_id"),
            rs.getString("nickname"),
            rs.getString("real_name"),
            rs.getString("mobile"),
            rs.getString("email"),
            rs.getString("member_level"),
            rs.getLong("points")
    );
    private static final RowMapper<Address> ADDRESS_MAPPER = (rs, row) -> new Address(
            rs.getString("address_id"),
            rs.getString("recipient_name"),
            rs.getString("mobile"),
            rs.getString("province"),
            rs.getString("city"),
            rs.getString("district"),
            rs.getString("detail"),
            rs.getBoolean("is_default")
    );

    public H2UserIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<UserProfile> findProfile(String tenantId, String userId) {
        return jdbc.query("SELECT * FROM agent_user_profile WHERE tenant_id = ? AND user_id = ?",
                PROFILE_MAPPER, tenantId, userId).stream().findFirst();
    }

    @Override
    public List<Address> findAddresses(String tenantId, String userId) {
        return jdbc.query("SELECT * FROM agent_user_address WHERE tenant_id = ? AND user_id = ?",
                ADDRESS_MAPPER, tenantId, userId);
    }
}
