package io.github.yysf1949.rag.agent.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentChannelTest {

    @Test
    void includesFourChannels() {
        assertThat(AgentChannel.values())
                .containsExactly(
                        AgentChannel.HTTP,
                        AgentChannel.WECHAT,
                        AgentChannel.EMAIL,
                        AgentChannel.APP);
    }

    @Test
    void isHumanFacingReflectsCustomerOrigin() {
        assertThat(AgentChannel.HTTP.isHumanFacing()).isTrue();
        assertThat(AgentChannel.WECHAT.isHumanFacing()).isTrue();
        assertThat(AgentChannel.EMAIL.isHumanFacing()).isTrue();
        assertThat(AgentChannel.APP.isHumanFacing()).isTrue();
    }

    @Test
    void parsesFromHeader() {
        assertThat(AgentChannel.fromHeader("wechat")).isEqualTo(AgentChannel.WECHAT);
        assertThat(AgentChannel.fromHeader("WECHAT-OA")).isEqualTo(AgentChannel.WECHAT);
        assertThat(AgentChannel.fromHeader(null)).isEqualTo(AgentChannel.HTTP);
    }
}
