package io.github.yysf1949.rag.agent.action;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个 Spring bean 方法声明为 Agent 可调用的工具。
 *
 * <h2>用法</h2>
 * <pre>
 *   {@code
 *   @Component
 *   public class KbSearchTool {
 *       @ToolSpec(
 *           name = "kb_search",
 *           description = "在租户知识库中检索相关文档片段",
 *           riskLevel = RiskLevel.L1_READ,
 *           idempotent = true
 *       )
 *       public SearchResult search(SearchRequest request) { ... }
 *   }
 *   }
 * </pre>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>name</b> — 模型调用时使用的工具名（必须是 kebab-case，Spring AI 1.0.9 限制）</li>
 *   <li><b>description</b> — 详细描述，告诉模型何时调用；写得越具体效果越好</li>
 *   <li><b>riskLevel</b> — 风险分级（对齐文章 4 级）</li>
 *   <li><b>idempotent</b> — 工具本身是否幂等（GET 类查询恒为 true）</li>
 *   <li><b>requiresIdempotencyKey</b> — 是否强制调用方传幂等键（写操作必须为 true）</li>
 * </ul>
 *
 * <h2>升级路径</h2>
 * <p>本注解是项目自有抽象，<b>不</b>直接使用 Spring AI 2.0 的 {@code @Tool}。
 * 升级 2.0 时由 {@code SpringAiAgentAdapter} 桥接。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolSpec {

    /** 工具名，kebab-case，例如 {@code "kb_search"} / {@code "create_reminder_ticket"} */
    String name();

    /** 详细描述（≥ 20 字）。模型据此判断何时调用。 */
    String description();

    /** 风险分级（默认 L1，开发者必须显式标注 L2+） */
    RiskLevel riskLevel() default RiskLevel.L1_READ;

    /** 工具是否幂等。L1 只读工具通常为 true，写操作为 false（依赖 idempotencyKey） */
    boolean idempotent() default true;

    /** 写操作是否强制要求 idempotencyKey（L2+ 推荐 true） */
    boolean requiresIdempotencyKey() default false;
}