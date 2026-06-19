package io.github.yysf1949.rag.agent.e2e;

import io.github.yysf1949.rag.agent.web.AgentController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * 集成测试专用配置 — 在 {@link io.github.yysf1949.rag.agent.AgentTestConfiguration}
 * 基础上增加 {@code @ComponentScan}，让 Spring Boot 扫描所有 @Component Bean。
 *
 * <p>rag-agent 是 leaf 模块，没有 @SpringBootApplication。
 * {@code @EnableAutoConfiguration} 单独使用不会触发 component scanning，
 * 需要显式声明 @ComponentScan。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "io.github.yysf1949.rag.agent")
@Import(AgentController.class)
public class IntegrationTestConfiguration {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
