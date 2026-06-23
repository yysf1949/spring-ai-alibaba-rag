package io.github.yysf1949.rag.agent;

import io.github.yysf1949.rag.agent.web.AgentController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 纯标记类 — 给 {@code @WebMvcTest} / {@code @SpringBootTest} 提供
 * {@code @SpringBootConfiguration} 锚点（Spring Boot 测试上下文需要这个根）。
 *
 * <p>为什么单独建一个：
 * <ul>
 *   <li>rag-agent 是 leaf 模块，没有 {@code @SpringBootApplication}。
 *       跨模块引用 rag-app 的 {@code RagAppApplication} 会反向依赖，破坏分层。</li>
 *   <li>{@code @EnableAutoConfiguration} 让 Spring Boot 走标准 auto-config 路径，
 *       单测里靠 {@code @MockBean} 覆盖掉真实 Bean（比如 {@code AgentService}）。</li>
 *   <li>{@code @Import(AgentController.class)} — {@code @WebMvcTest} 走 slice
 *       加载只把显式 include 的 Controller 拉进 context。rag-agent 没有
 *       {@code @ComponentScan} 自动扫描 web 包，所以这里手动 Import 一次。</li>
 *   <li>放在 {@code src/test/java} 不会污染 production classpath。</li>
 * </ul>
 *
 * <p>注意：这个类只用于测试根，<b>不要</b>在 main 目录里建对应的启动类 ——
 * rag-agent 设计为可复用库，宿主启动（rag-app）由 {@code RagAppApplication} 负责。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AgentController.class)
public class AgentTestConfiguration {
}
