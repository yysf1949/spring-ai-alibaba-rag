# Phase 9 — Agent Action Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `spring-ai-alibaba-rag` 项目中新增 `rag-agent` 模块，把企业后端 Service 改造成 AI Agent 可安全调用的"动作层（Action Layer）"，实现"知识库查询 + 提醒工单创建"端到端 Agent 闭环，并对齐「路条编程」AI 客服文章的核心方法论（5 层架构 + 4 级工具风险分级 + 幂等中间件 + 审计可观测）。

**Architecture:** 3 层简化版（编排层 / 动作层 / 治理层），其中：
- **编排层**（rag-agent/orchestration）：意图理解 + 工具选择 + Spring AI 1.0.9 `FunctionCallingCallback` 集成；
- **动作层**（rag-agent/action）：`@ToolSpec` 注解 + `ToolDescriptor` 注册中心 + 4 级风险分级（`L1_READ` / `L2_REVERSIBLE` / `L3_BUSINESS_STATE` / `L4_HIGH_RISK`）；
- **治理层**（rag-agent/governance）：身份传递 + 幂等键校验 + 风险门控 + 审计钩子（**复用** `rag-core` 已有的 `AuditEvent` + `LlmAuditHook`）；
- **示例业务（教学 demo）**：「查询知识库 + 创建提醒工单」——查询类复用现有 `QAService`（只读 L1），工单类用 `InMemoryTicketRepository`（L2 可逆低风险）。

**Tech Stack:**
- Spring Boot 3.3.5（与 rag-core 一致）
- Java 21（项目基线）
- Spring AI 1.0.9（**保持不升级**，仅使用 `FunctionCallingCallback` + `FunctionCallback` 接口，**预留 ToolDescriptor 抽象层**以兼容未来 2.0 `@Tool`）
- Maven 多 module（新增 `rag-agent` 子模块，依赖 `rag-core` + `rag-pipeline`，可选 `rag-redis`）
- Resilience4j 2.2.0（治理层用 `@Retry`/`@RateLimiter`）
- Micrometer 1.14.x（治理层指标发布）
- JUnit 5 + Mockito + AssertJ（项目既有测试栈）

**参考文章**：「路条编程」《Salesforce 36 亿美元押注 AI 客服：Java 后端真正的机会，不是接个大模型》(2026-06-17)，归档在 `~/ObsidianVault/AI研究/spring-ai-alibaba-rag/lessons-summary/2026-06-18-公众号-AI客服Action-Layer-路条编程.md`。

---

## 既有约束（设计时必须遵守）

1. **`rag-core` 是 leaf 模块**：只允许 `slf4j-api` + `commons-lang3`，**禁止**依赖 Spring / Spring AI / Redis。
2. **Port-and-Adapter 架构**：外部依赖都是接口，实现可替换。
3. **审计走 `AuditEvent` + `LlmAuditHook` 已建管道**——不要新建另一套。
4. **租户硬墙**：`tenantId` 永不跨用户，所有 Tool 调用必须带 `tenantId`。
5. **可观测先行**：每个新组件必须有 Micrometer 指标 + MDC 字段。
6. **测试金字塔**：单测覆盖每个组件 80%+，不依赖真实 Redis/LLM。
7. **Surgical 变更**：本 Phase 不修改 rag-core / rag-pipeline / rag-redis 任何业务代码（仅在 pom.xml 增 rag-core 引用即可）。

---

## 文件结构

```
spring-ai-alibaba-rag/
├── pom.xml                                  # Modify: 增加 <module>rag-agent</module>
├── rag-agent/                               # Create: 新模块
│   ├── pom.xml                              # Create: 依赖 rag-core + rag-pipeline + spring-ai
│   └── src/
│       ├── main/
│       │   ├── java/io/github/yysf1949/rag/agent/
│       │   │   ├── package-info.java        # 模块入口
│       │   │   ├── api/                     # 公开 API（AgentService、AgentRequest、AgentResponse）
│       │   │   │   ├── AgentService.java
│       │   │   │   ├── AgentRequest.java
│       │   │   │   └── AgentResponse.java
│       │   │   ├── action/                  # 动作层：ToolSpec 注解 + 注册中心 + 风险分级
│       │   │   │   ├── ToolSpec.java        # 注解
│       │   │   │   ├── ToolDescriptor.java  # 元数据 record
│       │   │   │   ├── RiskLevel.java       # enum L1/L2/L3/L4
│       │   │   │   ├── ToolRegistry.java    # Spring bean，扫描 @ToolSpec
│       │   │   │   └── InMemoryToolRegistry.java
│       │   │   ├── governance/              # 治理层：身份/幂等/风险门控/审计
│       │   │   │   ├── AgentIdentity.java        # record: tenantId, userId, sessionId, roles
│       │   │   │   ├── IdempotencyKey.java       # record + 校验器
│       │   │   │   ├── IdempotencyStore.java     # interface
│       │   │   │   ├── InMemoryIdempotencyStore.java
│       │   │   │   ├── ToolInvocationContext.java # 透传上下文
│       │   │   │   ├── RiskGate.java             # interface
│       │   │   │   ├── DefaultRiskGate.java      # 风险门控默认实现
│       │   │   │   └── ToolAuditBridge.java      # 桥接到现有 LlmAuditHook
│       │   │   ├── orchestration/           # 编排层：意图理解 + 工具调用循环
│       │   │   │   ├── AgentLoop.java           # interface: 决策+执行循环
│       │   │   │   ├── DefaultAgentLoop.java
│       │   │   │   └── SpringAiAgentAdapter.java # Spring AI FunctionCallingCallback 适配器
│       │   │   ├── builtin/                 # 内置工具（demo 用）
│       │   │   │   ├── KbSearchTool.java       # 包装 QAService（只读 L1）
│       │   │   │   ├── TicketTool.java         # 创建提醒工单（L2 可逆）
│       │   │   │   └── InMemoryTicketRepository.java
│       │   │   └── exception/
│       │   │       ├── ToolNotFoundException.java
│       │   │       ├── ToolRiskDeniedException.java
│       │   │       └── IdempotencyConflictException.java
│       │   └── resources/
│       │       └── (空)
│       └── test/
│           └── java/io/github/yysf1949/rag/agent/
│               ├── action/
│               │   ├── ToolRegistryTest.java
│               │   └── RiskLevelTest.java
│               ├── governance/
│               │   ├── IdempotencyKeyTest.java
│               │   ├── InMemoryIdempotencyStoreTest.java
│               │   ├── DefaultRiskGateTest.java
│               │   └── ToolAuditBridgeTest.java
│               ├── orchestration/
│               │   ├── DefaultAgentLoopTest.java
│               │   └── SpringAiAgentAdapterTest.java
│               ├── builtin/
│               │   ├── KbSearchToolTest.java
│               │   ├── TicketToolTest.java
│               │   └── InMemoryTicketRepositoryTest.java
│               └── e2e/
│                   └── AgentEndToEndTest.java    # Stub QA + 真实工单创建
└── docs/
    ├── architecture.md                            # Modify: 增加 §9 Agent Action Layer
    ├── design-principles.md                       # Modify: 增 §13 工具风险分级原则
    ├── observability.md                           # Modify: 增 §10 Agent 治理指标
    └── superpowers/
        └── plans/
            └── 2026-06-18-agent-action-layer.md   # 本文件
```

**新模块总文件数**: 22 个 Java + 1 个 pom.xml + 4 个文档修改。**预计代码量**: ~1800 行（含测试）。

---

## Task 1: 新增 rag-agent 模块骨架

**Files:**
- Modify: `pom.xml` (root) — 增加 `<module>rag-agent</module>`
- Create: `rag-agent/pom.xml` — 子模块定义
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/package-info.java`

- [ ] **Step 1.1: 修改根 pom.xml 增加 rag-agent 子模块**

在 `<modules>` 列表内（按字母序）增加 `rag-agent`：

```xml
<modules>
    <module>rag-agent</module>     <!-- 新增 -->
    <module>rag-core</module>
    <module>rag-embedding</module>
    <module>rag-pipeline</module>
    <module>rag-redis</module>
    <module>rag-app</module>
    <module>rag-test</module>
</modules>
```

- [ ] **Step 1.2: 创建 rag-agent/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.yysf1949.rag</groupId>
        <artifactId>spring-ai-alibaba-rag</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>rag-agent</artifactId>
    <name>rag-agent</name>
    <description>
        Agent Action Layer — 把企业后端 Service 改造成 AI Agent 可安全调用的工具集。
        3 层架构（编排 / 动作 / 治理），对齐「路条编程」AI 客服文章方法论。
        Tool Calling 走 Spring AI 1.0.9 FunctionCallingCallback，预留 ToolDescriptor 抽象层
        以兼容未来 2.0 @Tool 升级。
    </description>

    <dependencies>
        <!-- 领域模型 + port 接口（leaf 模块） -->
        <dependency>
            <groupId>io.github.yysf1949.rag</groupId>
            <artifactId>rag-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- 编排层需要 Spring AI 1.0.9 的 FunctionCallingCallback 接口 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
        </dependency>

        <!-- Spring 上下文（编排层用 Bean 注入；治理层用 @ConditionalOnProperty） -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>

        <!-- Resilience4j：治理层风险门控需要 @Retry / @RateLimiter -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
        </dependency>

        <!-- Micrometer 指标 -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 1.3: 创建 package-info.java**

文件路径: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/package-info.java`

```java
/**
 * rag-agent — Agent Action Layer (Phase 9).
 *
 * <p>把企业后端 Service 改造成 AI Agent 可安全调用的工具集。
 * 3 层架构（编排 / 动作 / 治理），对齐「路条编程」AI 客服文章方法论。</p>
 *
 * <h2>模块分层</h2>
 * <ul>
 *   <li><b>orchestration</b> — 意图理解 + 工具选择 + 调用循环，集成 Spring AI
 *       {@code FunctionCallingCallback}。未来升级 2.0 时只改
 *       {@code SpringAiAgentAdapter}。</li>
 *   <li><b>action</b> — 工具注册中心（{@code @ToolSpec} + {@code ToolRegistry}），
 *       4 级风险分级（{@code RiskLevel} L1-L4）。</li>
 *   <li><b>governance</b> — 身份传递 + 幂等键校验 + 风险门控 + 审计钩子。
 *       审计走 {@code rag-core} 已有的 {@code LlmAuditHook} 桥接。</li>
 *   <li><b>builtin</b> — 内置工具（demo 用）：{@code KbSearchTool}（包装
 *       {@code QAService}，L1 只读）+ {@code TicketTool}（创建提醒工单，L2）。</li>
 * </ul>
 *
 * <h2>Tool Calling 升级路径</h2>
 * <p>本模块刻意不直接使用 {@code @Tool} 注解（Spring AI 2.0），仅使用
 * {@code FunctionCallback} 接口（1.0.9），业务侧只依赖
 * {@code ToolDescriptor} 抽象层。升级 2.0 时改
 * {@code SpringAiAgentAdapter} 一个文件即可。</p>
 *
 * <h2>参考</h2>
 * <ul>
 *   <li>「路条编程」AI 客服文章（2026-06-17）</li>
 *   <li>本项目设计 spec §22 (Agent Action Layer)</li>
 *   <li>设计原则 12 条 §13（工具风险分级，Phase 9 新增）</li>
 * </ul>
 */
package io.github.yysf1949.rag.agent;
```

- [ ] **Step 1.4: 验证模块能被识别**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent -am validate -q
```

预期: `BUILD SUCCESS`，且 `target/` 目录生成。

- [ ] **Step 1.5: Commit**

```bash
git add pom.xml rag-agent/pom.xml rag-agent/src/main/java/io/github/yysf1949/rag/agent/package-info.java
git commit -m "feat(agent): scaffold rag-agent module skeleton (Phase 9 Task 1)"
```

---

## Task 2: 动作层 — `RiskLevel` 枚举 + `@ToolSpec` 注解

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/RiskLevel.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolSpec.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/action/RiskLevelTest.java`

- [ ] **Step 2.1: 写 RiskLevel 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/action/RiskLevelTest.java`

```java
package io.github.yysf1949.rag.agent.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskLevelTest {

    @Test
    void parseL1Read() {
        assertThat(RiskLevel.parse("L1_READ")).isEqualTo(RiskLevel.L1_READ);
        assertThat(RiskLevel.parse("l1_read")).isEqualTo(RiskLevel.L1_READ);
    }

    @Test
    void parseL4HighRisk() {
        assertThat(RiskLevel.parse("L4_HIGH_RISK")).isEqualTo(RiskLevel.L4_HIGH_RISK);
    }

    @Test
    void parseUnknownThrows() {
        assertThatThrownBy(() -> RiskLevel.parse("L5_FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L5_FOO");
    }

    @Test
    void requiresConfirmationFromL3() {
        assertThat(RiskLevel.L1_READ.requiresConfirmation()).isFalse();
        assertThat(RiskLevel.L2_REVERSIBLE.requiresConfirmation()).isFalse();
        assertThat(RiskLevel.L3_BUSINESS_STATE.requiresConfirmation()).isTrue();
        assertThat(RiskLevel.L4_HIGH_RISK.requiresConfirmation()).isTrue();
    }

    @Test
    void ordersMonotonically() {
        assertThat(RiskLevel.L1_READ.ordinal()).isLessThan(RiskLevel.L2_REVERSIBLE.ordinal());
        assertThat(RiskLevel.L2_REVERSIBLE.ordinal()).isLessThan(RiskLevel.L3_BUSINESS_STATE.ordinal());
        assertThat(RiskLevel.L3_BUSINESS_STATE.ordinal()).isLessThan(RiskLevel.L4_HIGH_RISK.ordinal());
    }
}
```

- [ ] **Step 2.2: 运行测试确认 FAIL**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RiskLevelTest -q
```

预期: `BUILD FAILURE`，`RiskLevel` 找不到。

- [ ] **Step 2.3: 实现 RiskLevel 枚举**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/RiskLevel.java`

```java
package io.github.yysf1949.rag.agent.action;

import java.util.Arrays;

/**
 * Tool 风险分级 — 对齐「路条编程」AI 客服文章 §"查询 ≠ 执行，必须拆开" 节。
 *
 * <h2>4 级定义</h2>
 * <ul>
 *   <li><b>L1_READ</b> — 只读工具（查询订单、查询物流、查询退款规则）。可自动执行。</li>
 *   <li><b>L2_REVERSIBLE</b> — 可逆低风险（创建草稿、生成工单、创建提醒）。
 *       可执行但结果不立即影响核心业务。</li>
 *   <li><b>L3_BUSINESS_STATE</b> — 改业务态（取消订单、创建退款、补发优惠券）。
 *       必须有幂等机制，且需用户二次确认。</li>
 *   <li><b>L4_HIGH_RISK</b> — 高风险（人工改价、直接退款、修改用户权限、删除数据）。
 *       不应直接由模型执行，必须走人工审批。</li>
 * </ul>
 *
 * <h2>升级路径</h2>
 * <p>本枚举是 Action Layer 的稳定契约。Spring AI 版本升级（1.0.9 → 2.0+）不影响。</p>
 */
public enum RiskLevel {

    L1_READ,
    L2_REVERSIBLE,
    L3_BUSINESS_STATE,
    L4_HIGH_RISK;

    /**
     * 该风险级是否需要用户二次确认才能执行。
     *
     * <p>对应文章论断："低风险自动 / 高风险辅助（Agent 在转人工前把
     * 身份、订单、规则、风险说明都准备好）。"</p>
     */
    public boolean requiresConfirmation() {
        return this == L3_BUSINESS_STATE || this == L4_HIGH_RISK;
    }

    /**
     * 严格大小写不敏感的解析，调用方拿到的可能是模型输出的字符串。
     */
    public static RiskLevel parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("RiskLevel raw is null");
        }
        String normalized = raw.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(l -> l.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown RiskLevel: " + raw + ", expected one of " + Arrays.toString(values())));
    }
}
```

- [ ] **Step 2.4: 写 @ToolSpec 注解**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolSpec.java`

```java
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
```

- [ ] **Step 2.5: 写 ToolDescriptor record**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java`

```java
package io.github.yysf1949.rag.agent.action;

import java.lang.reflect.Method;

/**
 * 工具元数据 — 由 {@code ToolRegistry} 在启动时扫描 {@code @ToolSpec} 注解生成。
 *
 * <p>业务侧（编排层、治理层）只依赖此 record，不直接接触反射 Method。
 * 升级 Spring AI 2.0 时，由 {@code SpringAiAgentAdapter} 负责
 * 把 {@code ToolDescriptor} 翻译成 {@code FunctionCallback}，业务侧无感。</p>
 *
 * @param name           kebab-case 工具名
 * @param description    详细描述（来自 @ToolSpec.description）
 * @param riskLevel      风险分级
 * @param idempotent     工具本身是否幂等
 * @param requiresIdempotencyKey 是否强制 idempotencyKey
 * @param bean           Spring bean 实例
 * @param method         反射 Method（参数类型是业务侧 DTO，参数顺序由声明决定）
 */
public record ToolDescriptor(
        String name,
        String description,
        RiskLevel riskLevel,
        boolean idempotent,
        boolean requiresIdempotencyKey,
        Object bean,
        Method method
) {

    /**
     * Spring AI 1.0.9 的 FunctionCallback 不支持复杂对象参数，故此约束：
     * 工具方法必须有且仅有一个参数，且参数类型是简单 DTO（POJO + 标准 getter）。
     * 该校验在 ToolRegistry 扫描时执行。
     */
    public void validate() {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] method must accept exactly 1 parameter, got %d",
                    name, method.getParameterCount()));
        }
        if (method.getReturnType() == void.class) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] method must have a return type (void is not allowed)", name));
        }
    }

    public Object invoke(Object request) throws Exception {
        return method.invoke(bean, request);
    }
}
```

- [ ] **Step 2.6: 跑测试确认 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RiskLevelTest -q
```

预期: `BUILD SUCCESS`，5 个用例全过。

- [ ] **Step 2.7: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): action layer — RiskLevel enum + @ToolSpec + ToolDescriptor (Phase 9 Task 2)"
```

---

## Task 3: 动作层 — `ToolRegistry` 扫描 + 注册

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolRegistry.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/InMemoryToolRegistry.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/action/ToolRegistryTest.java`

- [ ] **Step 3.1: 写 ToolRegistry 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/action/ToolRegistryTest.java`

```java
package io.github.yysf1949.rag.agent.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Component
    static class FakeTool {
        public record Input(String q) {}
        public record Output(String answer) {}

        @ToolSpec(name = "fake_search", description = "Fake search", riskLevel = RiskLevel.L1_READ)
        public Output search(Input input) {
            return new Output("found: " + input.q());
        }
    }

    @Component
    static class BadTool {
        public record Input(String x) {}

        @ToolSpec(name = "no_return", description = "Void return is not allowed")
        public void doNothing(Input input) { }
    }

    private InMemoryToolRegistry registry;

    @BeforeEach
    void setUp() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(FakeTool.class, BadTool.class);
            ctx.refresh();
            // For BadTool test we need a separate context
            registry = new InMemoryToolRegistry();
            registry.scanFromContext(ctx);
        }
    }

    @Test
    void scansToolAnnotatedMethod() throws Exception {
        assertThat(registry.listNames()).contains("fake_search");
        ToolDescriptor desc = registry.get("fake_search");
        assertThat(desc.riskLevel()).isEqualTo(RiskLevel.L1_READ);
        assertThat(desc.description()).contains("Fake search");
    }

    @Test
    void invokeRunsTheTool() throws Exception {
        ToolDescriptor desc = registry.get("fake_search");
        Object out = desc.invoke(new FakeTool.Input("hello"));
        assertThat(out).isInstanceOf(FakeTool.Output.class);
        assertThat(((FakeTool.Output) out).answer()).isEqualTo("found: hello");
    }

    @Test
    void missingToolThrows() {
        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void voidReturnToolFailsValidation() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(BadTool.class);
            ctx.refresh();
            var fresh = new InMemoryToolRegistry();
            assertThatThrownBy(() -> fresh.scanFromContext(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no_return");
        }
    }
}
```

注意：上面引用了 `ToolNotFoundException`，本 Task 暂未创建该类；测试会先编译失败——这是 TDD 节奏：先建测试，等 Step 3.3 写实现时同步建 exception。

- [ ] **Step 3.2: 创建 ToolNotFoundException**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/ToolNotFoundException.java`

```java
package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * Agent 找不到指定工具时抛出。
 *
 * <p>继承 {@code RagException} 以复用现有的
 * {@code RagExceptionHandler}（{@code rag-app/web/RagExceptionHandler}）。
 * HTTP 状态码：404。</p>
 */
public class ToolNotFoundException extends RagException {

    public ToolNotFoundException(String toolName) {
        super("Tool not found: " + toolName);
    }
}
```

- [ ] **Step 3.3: 写 ToolRegistry 接口 + 实现**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolRegistry.java`

```java
package io.github.yysf1949.rag.agent.action;

import java.util.List;

/**
 * 工具注册中心 — 编排层调用 {@code get(name)} 拿到 {@code ToolDescriptor} 后执行。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>Spring 启动 → {@code InMemoryToolRegistry.scanFromContext(ctx)} 扫描所有
 *       {@code @ToolSpec} 方法</li>
 *   <li>业务调用 {@code agentService.execute(req)} → 编排层通过
 *       {@code registry.get(name)} 拿 descriptor</li>
 *   <li>治理层（risk gate / idempotency / audit）校验通过后 → 反射调用</li>
 * </ol>
 */
public interface ToolRegistry {

    /** 扫描 Spring 上下文中的 {@code @ToolSpec} 方法。 */
    void scanFromContext(org.springframework.context.ApplicationContext ctx);

    /** 列出所有已注册工具名。 */
    List<String> listNames();

    /** 按名称获取描述符，不存在抛 {@link io.github.yysf1949.rag.agent.exception.ToolNotFoundException}。 */
    ToolDescriptor get(String name);
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/InMemoryToolRegistry.java`

```java
package io.github.yysf1949.rag.agent.action;

import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 {@code ToolRegistry} — 启动时扫描，运行时无锁查表。
 *
 * <p>选型理由：本项目 Tool 数量在两位数以内，启动一次性扫描足够。
 * 未来 Tool 上百可换 {@code Caffeine} 或扫库 DB。</p>
 */
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryToolRegistry.class);

    private final Map<String, ToolDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void scanFromContext(ApplicationContext ctx) {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(Component.class);
        for (Object bean : beans.values()) {
            for (Method m : bean.getClass().getMethods()) {
                ToolSpec spec = m.getAnnotation(ToolSpec.class);
                if (spec == null) continue;
                ToolDescriptor desc = new ToolDescriptor(
                        spec.name(),
                        spec.description(),
                        spec.riskLevel(),
                        spec.idempotent(),
                        spec.requiresIdempotencyKey(),
                        bean,
                        m);
                desc.validate();
                if (descriptors.containsKey(spec.name())) {
                    throw new IllegalStateException("Duplicate tool name: " + spec.name());
                }
                descriptors.put(spec.name(), desc);
                log.info("Registered tool [{}] riskLevel={} bean={} method={}",
                        spec.name(), spec.riskLevel(), bean.getClass().getSimpleName(), m.getName());
            }
        }
        log.info("ToolRegistry scan complete: {} tools registered", descriptors.size());
    }

    @Override
    public List<String> listNames() {
        return new ArrayList<>(descriptors.keySet());
    }

    @Override
    public ToolDescriptor get(String name) {
        ToolDescriptor d = descriptors.get(name);
        if (d == null) {
            throw new ToolNotFoundException(name);
        }
        return d;
    }
}
```

- [ ] **Step 3.4: 跑测试确认 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=ToolRegistryTest -q
```

预期: `BUILD SUCCESS`，4 个用例全过。

- [ ] **Step 3.5: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): action layer — ToolRegistry scan + lookup (Phase 9 Task 3)"
```

---

## Task 4: 治理层 — `AgentIdentity` + `IdempotencyKey` + `IdempotencyStore`

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentIdentity.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/IdempotencyKey.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/IdempotencyStore.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/InMemoryIdempotencyStore.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/IdempotencyConflictException.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/IdempotencyKeyTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/InMemoryIdempotencyStoreTest.java`

- [ ] **Step 4.1: 写 IdempotencyKey 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/IdempotencyKeyTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> IdempotencyKey.of("tenant1", "user1", "session-1", "create_ticket", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyToken");
    }

    @Test
    void stableHashIsOrderIndependent() {
        // The raw token is the source of truth; the hash is a compact form.
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.rawToken()).isEqualTo("tok-123");
    }

    @Test
    void differentTokensProduceDifferentKeys() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-A");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-B");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void recordEqualsAndHashCodeWork() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        var k2 = new IdempotencyKey("t1", "u1", "s1", "create_ticket", "tok-123", "h-abc");
        // rawToken and hash must both match for record equality
        assertThat(Objects.equals(k1, k2)).isTrue();
    }
}
```

- [ ] **Step 4.2: 写 InMemoryIdempotencyStore 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/InMemoryIdempotencyStoreTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.yysf1949.rag.agent.governance.IdempotencyStore.PutResult;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
    }

    @Test
    void firstPutReturnsFirst() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        PutResult result = store.putIfAbsent(key, "first-result");
        assertThat(result.isFirst()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void secondPutReturnsReplay() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        store.putIfAbsent(key, "first-result");
        PutResult result = store.putIfAbsent(key, "second-result");
        assertThat(result.isReplay()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void differentKeysAreIndependent() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-2");
        assertThat(store.putIfAbsent(k1, "v1").isFirst()).isTrue();
        assertThat(store.putIfAbsent(k2, "v2").isFirst()).isTrue();
    }
}
```

> **注意**: 本 Task 4.2 写的就是最终版（使用 `PutResult.isFirst()/isReplay()`），与 Task 4.5 的 `IdempotencyStore` 接口定义（Step 4.4 修正后）一致。

- [ ] **Step 4.3: 实现 AgentIdentity + IdempotencyKey**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentIdentity.java`

```java
package io.github.yysf1949.rag.agent.governance;

import java.util.Set;

/**
 * Agent 调用的身份上下文 — 在会话入口处由 HTTP filter 解析，
 * 透传到编排层 → 治理层 → 工具实现。
 *
 * <p>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"：
 * "如果一个普通用户只能查看自己的订单，那么 Agent 也只能以这个用户的
 * 身份查看自己的订单，不能因为 Agent 使用了后台服务账号，就获得
 * 查询所有订单的能力。"</p>
 *
 * <p>对齐本项目设计原则 §6 租户硬墙：{@code tenantId} 永不跨用户。</p>
 *
 * @param tenantId   租户 ID（永不跨用户）
 * @param userId     终端用户 ID
 * @param sessionId  会话 ID（用于幂等键拼接）
 * @param roles      角色列表（用于风险门控 / RBAC）
 */
public record AgentIdentity(
        String tenantId,
        String userId,
        String sessionId,
        Set<String> roles
) {

    public AgentIdentity {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/IdempotencyKey.java`

```java
package io.github.yysf1949.rag.agent.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等键 — 对齐「路条编程」文章 §"幂等是 AI 客服第一课"：
 *
 * <blockquote>
 * 每一个写操作工具都应该接收一个稳定的业务幂等键，而不是每次调用都
 * 临时生成新的请求 ID。幂等键可以由会话 ID、用户确认动作和业务对象
 * 共同生成，并写入 Redis 或数据库唯一索引。
 * </blockquote>
 *
 * <h2>构成</h2>
 * <pre>
 *   {tenantId}:{userId}:{sessionId}:{toolName}:{idempotencyToken}
 * </pre>
 *
 * <h2>{@code idempotencyToken} 来源</h2>
 * <p>由调用方提供（前端按钮点击 → 后端生成 token → 调用 Agent）。同一个
 * 用户操作同一个业务对象，整个会话期间 token 必须稳定。</p>
 */
public record IdempotencyKey(
        String tenantId,
        String userId,
        String sessionId,
        String toolName,
        String rawToken,
        String hash
) {

    public static IdempotencyKey of(String tenantId, String userId, String sessionId,
                                    String toolName, String idempotencyToken) {
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            throw new IllegalArgumentException("idempotencyToken must not be blank");
        }
        String composite = String.join(":", tenantId, userId,
                sessionId == null ? "_" : sessionId,
                toolName,
                idempotencyToken);
        return new IdempotencyKey(tenantId, userId, sessionId, toolName,
                idempotencyToken, sha256(composite));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 4.4: 实现 IdempotencyStore 接口 + 内存实现 + exception**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/IdempotencyStore.java`

```java
package io.github.yysf1949.rag.agent.governance;

/**
 * 幂等结果存储 — 治理层用。
 *
 * <p>{@code putIfAbsent} 语义：第一次调用记入并返回 {@code PutResult.isFirst()=true}
 * + 写入的值，后续相同 key 调用返回 {@code PutResult.isReplay()=true} + 上次缓存的结果值。</p>
 */
public interface IdempotencyStore {

    PutResult putIfAbsent(IdempotencyKey key, Object value);

    /** putIfAbsent 返回值。 */
    record PutResult(OutcomeKind outcome, Object value) {
        public enum OutcomeKind { FIRST, REPLAY }
        public boolean isFirst() { return outcome == OutcomeKind.FIRST; }
        public boolean isReplay() { return outcome == OutcomeKind.REPLAY; }
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/InMemoryIdempotencyStore.java`

```java
package io.github.yysf1949.rag.agent.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版 IdempotencyStore — 单实例部署够用；分布式部署可换 Redis 实现。
 *
 * <h2>升级路径</h2>
 * <p>生产建议：把 {@code Map} 换成 {@code rag-redis} 里的
 * {@code SETNX + EXPIRE} 模式（5 分钟 TTL 防膨胀）。</p>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        Object existing = store.putIfAbsent(key.hash(), value);
        if (existing == null) {
            return new PutResult(PutResult.OutcomeKind.FIRST, value);
        }
        return new PutResult(PutResult.OutcomeKind.REPLAY, existing);
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/IdempotencyConflictException.java`

```java
package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * 幂等键冲突 — 同一 key 第二次调用带不同参数时抛出。
 *
 * <p>本 Phase 不强制做"参数一致性"校验，留待 Phase 10。
 * 当前实现：第二次同 key 直接返回上次结果（REPLAY），不抛此异常。</p>
 */
public class IdempotencyConflictException extends RagException {
    public IdempotencyConflictException(String key) {
        super("Idempotency conflict for key: " + key);
    }
}
```

- [ ] **Step 4.5: 修正单测（IdempotencyStoreTest 改用新 PutResult）**

修改 `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/InMemoryIdempotencyStoreTest.java`：
- `result.outcome()` → `result.isFirst()` / `result.isReplay()`
- `InMemoryIdempotencyStore.Outcome.FIRST` → `PutResult.OutcomeKind.FIRST`（直接 import `PutResult`）

完整修正版本：

```java
package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.yysf1949.rag.agent.governance.IdempotencyStore.PutResult;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
    }

    @Test
    void firstPutReturnsFirst() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        PutResult result = store.putIfAbsent(key, "first-result");
        assertThat(result.isFirst()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void secondPutReturnsReplay() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        store.putIfAbsent(key, "first-result");
        PutResult result = store.putIfAbsent(key, "second-result");
        assertThat(result.isReplay()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void differentKeysAreIndependent() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-2");
        assertThat(store.putIfAbsent(k1, "v1").isFirst()).isTrue();
        assertThat(store.putIfAbsent(k2, "v2").isFirst()).isTrue();
    }
}
```

- [ ] **Step 4.6: 跑两个测试都 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest='IdempotencyKeyTest,InMemoryIdempotencyStoreTest' -q
```

预期: `BUILD SUCCESS`，7 个用例全过。

- [ ] **Step 4.7: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): governance — AgentIdentity + IdempotencyKey + IdempotencyStore (Phase 9 Task 4)"
```

---

## Task 5: 治理层 — `ToolInvocationContext` + `RiskGate` + `ToolAuditBridge`

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/ToolInvocationContext.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RiskGate.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGate.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/ToolAuditBridge.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/ToolRiskDeniedException.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGateTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/ToolAuditBridgeTest.java`

- [ ] **Step 5.1: 写 DefaultRiskGate 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGateTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRiskGateTest {

    static class FakeBean {
        public record Input(String x) {}
        public record Output(String y) {}

        public Output run(Input i) { return new Output(i.x() + "!"); }
    }

    private ToolDescriptor desc(RiskLevel level) throws NoSuchMethodException {
        Method m = FakeBean.class.getMethod("run", FakeBean.Input.class);
        return new ToolDescriptor("t", "d", level, true, false, new FakeBean(), m);
    }

    @Test
    void l1ReadAlwaysAllowed() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L1_READ), identity, null))
                .doesNotThrowAnyException();
    }

    @Test
    void l3RequiresIdempotencyKey() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L3_BUSINESS_STATE), identity, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void l4RequiresAdminRole() throws Exception {
        var gate = new DefaultRiskGate();
        var normalUser = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "dangerous", "tok-1");
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), normalUser, key))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("admin");

        var adminUser = new AgentIdentity("t1", "u1", "s1", Set.of("admin"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), adminUser, key))
                .doesNotThrowAnyException();
    }

    @Test
    void toolRequiresKeyButMissingFails() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        Method m = FakeBean.class.getMethod("run", FakeBean.Input.class);
        // requiresIdempotencyKey=true
        var tool = new ToolDescriptor("t2", "d", RiskLevel.L2_REVERSIBLE, false, true, new FakeBean(), m);
        assertThatThrownBy(() -> gate.check(tool, identity, null))
                .isInstanceOf(ToolRiskDeniedException.class);
    }
}
```

- [ ] **Step 5.2: 写 ToolAuditBridge 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/ToolAuditBridgeTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.port.LlmAuditHook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolAuditBridgeTest {

    @Test
    void recordsToolCallOnSuccess() {
        LlmAuditHook hook = mock(LlmAuditHook.class);
        var bridge = new ToolAuditBridge(hook);
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new ToolInvocationContext(identity, "kb_search",
                "{\"q\":\"hi\"}", "{\"results\":[]}", 42L, "SUCCESS");

        bridge.record(ctx);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // We just verify the call happened with non-blank content
        verify(hook).onLlmCall(
                org.mockito.ArgumentMatchers.eq("t1"),
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("s1"),
                captor.capture(),
                org.mockito.ArgumentMatchers.contains("kb_search"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("kb_search"),
                org.mockito.ArgumentMatchers.contains("SUCCESS"),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("SUCCESS"));
        assertThat(captor.getValue()).isNotBlank();
    }
}
```

- [ ] **Step 5.3: 实现 ToolInvocationContext**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/ToolInvocationContext.java`

```java
package io.github.yysf1949.rag.agent.governance;

/**
 * 一次工具调用的完整上下文 — 编排层构造，治理层消费，审计钩子记录。
 *
 * @param identity   调用者身份
 * @param toolName   工具名（来自 {@code ToolDescriptor.name}）
 * @param requestJson  请求 JSON（治理层审计用，不重复序列化）
 * @param responseJson 响应 JSON
 * @param latencyMs  实际执行耗时
 * @param outcome    SUCCESS / FAILURE / DENIED
 */
public record ToolInvocationContext(
        AgentIdentity identity,
        String toolName,
        String requestJson,
        String responseJson,
        long latencyMs,
        String outcome
) { }
```

- [ ] **Step 5.4: 实现 RiskGate + DefaultRiskGate + exception**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RiskGate.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.ToolDescriptor;

/**
 * 风险门控 — 编排层在调用工具前必须过 RiskGate.check()。
 *
 * <p>对齐「路条编程」文章 §"工具分级"：
 * "工具分级的价值，是把大模型的不确定性限制在可控范围内。"</p>
 */
public interface RiskGate {

    /**
     * @param descriptor  工具元数据
     * @param identity    调用者
     * @param idemKey     幂等键（L2+ 写操作必传；L1 可为 null）
     * @throws io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException 风险被拒
     */
    void check(ToolDescriptor descriptor, AgentIdentity identity, IdempotencyKey idemKey);
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGate.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 默认风险门控 — 实现文章 4 级规则。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>L1</b> — 全部放行</li>
 *   <li><b>L2</b> — 工具声明 {@code requiresIdempotencyKey=true} 时，调用方必须传</li>
 *   <li><b>L3</b> — 必须传 idempotencyKey（写操作兜底）</li>
 *   <li><b>L4</b> — 必须传 idempotencyKey + 调用者必须有 {@code admin} 角色</li>
 * </ul>
 */
@Component
public class DefaultRiskGate implements RiskGate {

    private static final Set<String> ADMIN_ROLES = Set.of("admin", "system");

    @Override
    public void check(ToolDescriptor descriptor, AgentIdentity identity, IdempotencyKey idemKey) {
        RiskLevel level = descriptor.riskLevel();
        boolean hasAdminRole = identity.roles().stream().anyMatch(ADMIN_ROLES::contains);

        if (level == RiskLevel.L1_READ) {
            return; // 全部放行
        }

        // L2+ 必须有幂等键
        if (idemKey == null) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] is %s; idempotencyKey is required", descriptor.name(), level));
        }

        // L4 额外要求 admin 角色
        if (level == RiskLevel.L4_HIGH_RISK && !hasAdminRole) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] is L4_HIGH_RISK; caller must have admin role, got %s",
                    descriptor.name(), identity.roles()));
        }

        // 工具声明 requiresIdempotencyKey 但调用方没传（idemKey 仍可能是 null 之前已拒；这里再校验 token 不为空）
        if (descriptor.requiresIdempotencyKey() && (idemKey.rawToken() == null || idemKey.rawToken().isBlank())) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] requires idempotencyKey with non-blank token", descriptor.name()));
        }
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/ToolRiskDeniedException.java`

```java
package io.github.yysf1949.rag.agent.exception;

import io.github.yysf1949.rag.core.exception.RagException;

/**
 * 工具风险被门控拒绝 — HTTP 状态码：403。
 */
public class ToolRiskDeniedException extends RagException {
    public ToolRiskDeniedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5.5: 实现 ToolAuditBridge（桥接 rag-core LlmAuditHook）**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/ToolAuditBridge.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.port.LlmAuditHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 工具调用审计桥接 — 复用 {@code rag-core} 已有的
 * {@link LlmAuditHook} 通道（spec §21，rag-app AuditChannel 实现）。
 *
 * <h2>为什么走 LlmAuditHook 而不是新写一套</h2>
 * <ul>
 *   <li>现有 audit appender（90 天 RollingFile）+ Kafka 出口直接复用</li>
 *   <li>对齐设计原则 §11 spec 优先 — 不要造平行管道</li>
 *   <li>治理层跟 LLM 调用的 audit 都进同一个通道，方便合规检索</li>
 * </ul>
 *
 * <h2>字段映射</h2>
 * <ul>
 *   <li>{@code queryHash} ← SHA-256(requestJson)</li>
 *   <li>{@code modelId}   ← 固定 {@code "agent:<toolName>"}</li>
 *   <li>{@code promptTemplate} ← 固定 {@code "agent-tool-call"}</li>
 *   <li>{@code promptBody} ← 工具名 + 请求 JSON</li>
 *   <li>{@code completion} ← 响应 JSON + outcome 标签</li>
 *   <li>{@code outcome}   ← SUCCESS / FAILURE / DENIED</li>
 * </ul>
 */
@Component
public class ToolAuditBridge {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditBridge.class);
    private static final String MODEL_PREFIX = "agent:";

    private final LlmAuditHook hook;

    public ToolAuditBridge(LlmAuditHook hook) {
        this.hook = hook == null ? LlmAuditHook.NOOP : hook;
    }

    public void record(ToolInvocationContext ctx) {
        try {
            String queryHash = sha256(ctx.requestJson() == null ? "" : ctx.requestJson());
            String modelId = MODEL_PREFIX + ctx.toolName();
            String promptBody = "tool=" + ctx.toolName() + " request=" + ctx.requestJson();
            String completion = ctx.responseJson() + " outcome=" + ctx.outcome();
            hook.onLlmCall(
                    ctx.identity().tenantId(),
                    ctx.identity().userId(),
                    ctx.identity().sessionId(),
                    queryHash,
                    modelId,
                    "agent-tool-call",
                    promptBody,
                    completion,
                    ctx.latencyMs(),
                    ctx.outcome());
        } catch (Exception e) {
            // LlmAuditHook contract: never throws. Absorb errors per spec §21.
            log.warn("ToolAuditBridge failed to record audit for tool [{}]: {}",
                    ctx.toolName(), e.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
```

- [ ] **Step 5.6: 跑测试确认 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest='DefaultRiskGateTest,ToolAuditBridgeTest' -q
```

预期: `BUILD SUCCESS`，两个测试类用例全过。

- [ ] **Step 5.7: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): governance — ToolInvocationContext + RiskGate + ToolAuditBridge (Phase 9 Task 5)"
```

---

## Task 6: 内置工具（demo）— `KbSearchTool` 包装 `QAService`

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/KbSearchTool.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/InMemoryTicketRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/TicketTool.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/KbSearchToolTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/TicketToolTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/InMemoryTicketRepositoryTest.java`

- [ ] **Step 6.1: 写 KbSearchTool 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/KbSearchToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbSearchToolTest {

    @Test
    void delegatesToQAService() {
        QAService qa = mock(QAService.class);
        var answer = new Answer("snippet", AnswerSource.LLM, null, null);
        when(qa.answer(any(Query.class))).thenReturn(answer);

        var tool = new KbSearchTool(qa);
        var out = tool.search(new KbSearchTool.Request("tenant1", "user1", "怎么退款", null, 5, null));

        assertThat(out.answerText()).isEqualTo("snippet");
        assertThat(out.source()).isEqualTo(AnswerSource.LLM);
    }
}
```

- [ ] **Step 6.2: 写 InMemoryTicketRepository 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/InMemoryTicketRepositoryTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTicketRepositoryTest {

    private InMemoryTicketRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTicketRepository();
    }

    @Test
    void saveAndFind() {
        var t = new TicketTool.Ticket("t1", "u1", "kb-search", "查询结果为空", "PENDING", null);
        repo.save(t);
        assertThat(repo.findById("t1")).isPresent();
    }

    @Test
    void findByTenantFilters() {
        repo.save(new TicketTool.Ticket("t1", "u1", "kb-search", "x", "PENDING", null));
        repo.save(new TicketTool.Ticket("t2", "u2", "kb-search", "y", "PENDING", null));
        assertThat(repo.findByTenant("t1")).hasSize(1);
        assertThat(repo.findByTenant("t2")).hasSize(1);
        assertThat(repo.findByTenant("t3")).isEmpty();
    }
}
```

- [ ] **Step 6.3: 写 TicketTool 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/TicketToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketToolTest {

    private InMemoryTicketRepository repo;
    private IdempotencyStore idem;
    private TicketTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTicketRepository();
        idem = new InMemoryIdempotencyStore();
        tool = new TicketTool(repo, idem);
    }

    @Test
    void createsTicketOnFirstCall() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", UUID.randomUUID().toString());
        var req = new TicketTool.Request("kb-search", "查询结果为空，请人工跟进");

        var resp = tool.createReminder(identity, key, req);

        assertThat(resp.ticketId()).isNotBlank();
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(repo.findById(resp.ticketId())).isPresent();
    }

    @Test
    void sameIdempotencyKeyReturnsSameTicket() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "stable-token-123");
        var req = new TicketTool.Request("kb-search", "query");

        var resp1 = tool.createReminder(identity, key, req);
        var resp2 = tool.createReminder(identity, key, req);

        assertThat(resp1.ticketId()).isEqualTo(resp2.ticketId());
        assertThat(repo.findByTenant("t1")).hasSize(1);
    }
}
```

- [ ] **Step 6.4: 实现 KbSearchTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/KbSearchTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索工具 — L1 只读。包装现有 {@code QAService}。
 *
 * <h2>为什么是 L1</h2>
 * <p>{@code QAService.answer} 不会修改任何业务数据 — 检索、rerank、LLM
 * 生成都是只读操作。文章 4 级里 L1 是"查询订单、查询物流、查询退款规则"
 * 之类，知识库查询天然属于 L1。</p>
 *
 * <h2>为什么 idem 默认为 true</h2>
 * <p>纯读，不改任何状态，重复调用结果一致（除非 KB 版本切换，本 Phase 暂不处理）。</p>
 */
@Component
public class KbSearchTool {

    private final QAService qaService;

    public KbSearchTool(QAService qaService) {
        this.qaService = qaService;
    }

    public record Request(
            String tenantId,
            String userId,
            String rawText,
            Set<String> permissionTags,
            int topK,
            Long kbVersion
    ) {
        public Request {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId required");
            }
            if (rawText == null || rawText.isBlank()) {
                throw new IllegalArgumentException("rawText required");
            }
            if (permissionTags == null) permissionTags = Set.of();
            if (topK <= 0) topK = 5;
        }
    }

    public record Response(
            String answerText,
            AnswerSource source,
            List<String> retrievedChunkIds
    ) { }

    @ToolSpec(
            name = "kb_search",
            description = "在租户知识库中检索相关文档片段并由 LLM 合成答案。"
                    + "纯读操作，不修改任何业务数据；适合回答用户关于产品/政策/规则的提问。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true)
    public Response search(Request request) {
        Query q = new Query(
                request.tenantId(),
                request.userId(),
                null, // sessionId 由 AgentIdentity 透传，本工具不重复
                request.rawText(),
                new HashSet<>(request.permissionTags()),
                request.topK(),
                request.kbVersion());
        Answer answer = qaService.answer(q);
        return new Response(answer.text(), answer.source(), List.of());
    }
}
```

- [ ] **Step 6.5: 实现 InMemoryTicketRepository + TicketTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/InMemoryTicketRepository.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提醒工单内存仓库 — 教学 demo 用。
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围只覆盖 L2 写操作的
 * 幂等 + 审计 + 风险门控的端到端跑通。</p>
 */
@Component
public class InMemoryTicketRepository {

    private final Map<String, TicketTool.Ticket> store = new ConcurrentHashMap<>();

    public TicketTool.Ticket save(TicketTool.Ticket t) {
        store.put(t.ticketId(), t);
        return t;
    }

    public Optional<TicketTool.Ticket> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<TicketTool.Ticket> findByTenant(String tenantId) {
        List<TicketTool.Ticket> out = new ArrayList<>();
        for (TicketTool.Ticket t : store.values()) {
            if (t.tenantId().equals(tenantId)) out.add(t);
        }
        return out;
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/TicketTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 提醒工单工具 — L2 可逆低风险。
 *
 * <h2>为什么是 L2</h2>
 * <p>创建工单是"创建草稿、生成工单、创建提醒"类操作 — 不立即影响核心业务
 * （订单/支付/库存），但会写入业务数据库。对应文章 L2 定义："可执行但
 * 结果不应该立即影响核心业务。"</p>
 *
 * <h2>幂等实现</h2>
 * <p>调用方必须传 {@code idempotencyKey}（{@code requiresIdempotencyKey=true}）。
 * 第二次同 key 调用直接返回上次结果，<b>不</b>创建第二张工单。</p>
 */
@Component
public class TicketTool {

    private final InMemoryTicketRepository repository;
    private final IdempotencyStore idempotencyStore;

    public TicketTool(InMemoryTicketRepository repository, IdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
    }

    public record Request(
            String sourceTool,
            String description
    ) {
        public Request {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description required");
            }
            if (sourceTool == null || sourceTool.isBlank()) sourceTool = "agent";
        }
    }

    public record Response(
            String ticketId,
            String status
    ) { }

    public record Ticket(
            String ticketId,
            String tenantId,
            String userId,
            String sourceTool,
            String description,
            String status,
            Instant createdAt
    ) { }

    @ToolSpec(
            name = "create_reminder_ticket",
            description = "为当前用户创建一条提醒工单（不修改订单/支付/库存）。"
                    + "适用于'kb_search 返回 FALLBACK_RULE / 用户表示要人工跟进'等场景。"
                    + "调用方必须传 idempotencyKey；同 key 重复调用返回上次结果。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = false,
            requiresIdempotencyKey = true)
    public Response createReminder(AgentIdentity identity, IdempotencyKey idempotencyKey, Request request) {
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            // 第一次写入时是 null —— 但因为是 REPLAY，意味着有上游调用方已创建过。
            // 我们改成"幂等 = 同步锁 + 结果回放"模式：第一次成功后再 write。
            // 简化：本 Phase 把 ticket id 直接放在 idempotencyStore value 里。
            String existingId = (String) put.value();
            if (existingId != null) {
                return new Response(existingId, "PENDING");
            }
        }

        // 第一次：创建 + 回填 idempotency
        String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8);
        Ticket ticket = new Ticket(
                ticketId,
                identity.tenantId(),
                identity.userId(),
                request.sourceTool(),
                request.description(),
                "PENDING",
                Instant.now());
        repository.save(ticket);

        // 用相同 key 再 put 一次把 ticketId 写回 — 第二次 REPLAY 时能拿回
        idempotencyStore.putIfAbsent(idempotencyKey, ticketId);

        return new Response(ticketId, "PENDING");
    }
}
```

注意：上面 `createReminder` 用了 `AgentIdentity + IdempotencyKey` 两个额外参数，但 `ToolDescriptor` 限定工具方法只能有 1 个参数。**这是 Task 7 编排层要解决的关键适配点**——编排层在 invoke 前会把 `AgentIdentity` 和 `IdempotencyKey` 注入到 Request 里，或者用 `ThreadLocal`/Context 传递。本 Task 先把工具签名定下来，Task 7 处理这个矛盾。

为了让 TicketTool 在本 Task 单独单测通过，方法签名直接用 3 个参数（`identity, key, request`），这在编排层调用时会通过 `Method.invoke` 透传（ToolDescriptor 校验暂时放宽）。在 Task 7 中我们会**放宽 validate** 允许额外参数（来自治理层），或者把 `identity` 和 `key` 放进 `Request` 的 wrapper 里。

**Task 7 的决策**：把 `ToolDescriptor.validate` 改为**允许 1-3 个参数**，且 `AgentIdentity` + `IdempotencyKey` 类型由编排层按位置注入。

- [ ] **Step 6.6: 跑测试确认 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest='KbSearchToolTest,TicketToolTest,InMemoryTicketRepositoryTest' -q
```

预期: `BUILD SUCCESS`，3 个测试类用例全过。

- [ ] **Step 6.7: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): builtin tools — KbSearchTool (L1) + TicketTool (L2) (Phase 9 Task 6)"
```

---

## Task 7: 编排层 — `DefaultAgentLoop` + `SpringAiAgentAdapter` + `AgentService`

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentRequest.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentResponse.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentService.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/AgentLoop.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoop.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/SpringAiAgentAdapter.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java` (放宽 validate)
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoopTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/SpringAiAgentAdapterTest.java`

- [ ] **Step 7.1: 写 DefaultAgentLoop 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoopTest.java`

```java
package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentLoopTest {

    private DefaultAgentLoop loop;
    private ToolRegistry registry;
    private List<ToolInvocationContext> auditTrail;

    @BeforeEach
    void setUp() {
        QAService qa = mock(QAService.class);
        when(qa.answer(any(Query.class))).thenReturn(
                new Answer("退款政策：7 天无理由", AnswerSource.LLM, null, null));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(QAService.class, () -> qa);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, DefaultRiskGate.class);
            ctx.refresh();
            registry = ctx.getBean(InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
        }

        auditTrail = new ArrayList<>();
        ToolAuditBridge bridge = new ToolAuditBridge(new LlmAuditHook() {
            @Override public void onLlmCall(String t, String u, String s, String q, String m, String pt, String pb, String c, long l, String o) {
                auditTrail.add(new ToolInvocationContext(new AgentIdentity(t, u, s, Set.of()), m, pb, c, l, o));
            }
        });
        IdempotencyStore idem = new InMemoryIdempotencyStore();
        RiskGate gate = new DefaultRiskGate();
        loop = new DefaultAgentLoop(registry, gate, idem, bridge);
    }

    @Test
    void executesToolByName() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var req = AgentRequest.of(identity, "kb_search",
                new KbSearchTool.Request("t1", "u1", "怎么退款", Set.of(), 5, null),
                null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo("SUCCESS");
        assertThat(resp.toolName()).isEqualTo("kb_search");
        assertThat(auditTrail).hasSize(1);
        assertThat(auditTrail.get(0).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void writeToolRequiresIdempotencyKey() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var req = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "请人工跟进"),
                null); // 没有 idempotencyKey
        assertThatThrownBy(() -> loop.execute(req))
                .isInstanceOf(ToolRiskDeniedException.class);
    }

    @Test
    void writeToolReplaysOnSameKey() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-A");
        var req1 = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "first"), key);
        var req2 = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "second"), key);
        var r1 = loop.execute(req1);
        var r2 = loop.execute(req2);
        assertThat(r1.toolResponse()).isEqualTo(r2.toolResponse()); // same ticket id
    }
}
```

- [ ] **Step 7.2: 写 SpringAiAgentAdapter 单测（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/SpringAiAgentAdapterTest.java`

```java
package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiAgentAdapterTest {

    static class FakeBean {
        public record In(String q) {}
        public record Out(String a) {}

        public Out run(In i) { return new Out("ok:" + i.q()); }
    }

    @Test
    void registersFunctionCallbackForEachTool() throws Exception {
        // 用真实 InMemoryToolRegistry + 一个手动注册的 ToolDescriptor
        var registry = new io.github.yysf1949.rag.agent.action.InMemoryToolRegistry();
        Method m = FakeBean.class.getMethod("run", FakeBean.In.class);
        var desc = new ToolDescriptor("fake_tool", "Fake tool", RiskLevel.L1_READ, true, false, new FakeBean(), m);
        // 手动塞进 registry（不走 scan）
        var field = io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class.getDeclaredField("descriptors");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, ToolDescriptor>) field.get(registry);
        map.put("fake_tool", desc);

        var adapter = new SpringAiAgentAdapter(registry);
        var callbacks = adapter.getFunctionCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getName()).isEqualTo("fake_tool");
    }

    @Test
    void throwsWhenToolMissing() {
        var registry = new io.github.yysf1949.rag.agent.action.InMemoryToolRegistry();
        var adapter = new SpringAiAgentAdapter(registry);
        assertThatThrownBy(() -> adapter.invoke("nope", "{}"))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void invokeReturnsJsonString() {
        var registry = new io.github.yysf1949.rag.agent.action.InMemoryToolRegistry();
        try {
            var field = io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class.getDeclaredField("descriptors");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, ToolDescriptor>) field.get(registry);
            Method m = FakeBean.class.getMethod("run", FakeBean.In.class);
            map.put("fake_tool", new ToolDescriptor("fake_tool", "Fake", RiskLevel.L1_READ, true, false, new FakeBean(), m));
        } catch (Exception e) { throw new RuntimeException(e); }

        var adapter = new SpringAiAgentAdapter(registry);
        String result = adapter.invoke("fake_tool", "{\"q\":\"hi\"}");
        assertThat(result).contains("ok:hi");
    }
}
```

- [ ] **Step 7.3: 放宽 ToolDescriptor.validate 允许额外参数**

修改 `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java`，把 `validate` 改为：

```java
public void validate() {
    Class<?>[] params = method.getParameterTypes();
    if (params.length < 1 || params.length > 3) {
        throw new IllegalStateException(String.format(
                "Tool [%s] must accept 1-3 parameters (AgentIdentity, IdempotencyKey, Request), got %d",
                name, params.length));
    }
    if (method.getReturnType() == void.class) {
        throw new IllegalStateException(String.format(
                "Tool [%s] method must have a return type (void is not allowed)", name));
    }
    // 第一个业务参数（最后一个非特殊类型）必须是 POJO，非 String/primitive
    Class<?> businessParam = params[params.length - 1];
    if (businessParam.isPrimitive() || businessParam == String.class
            || businessParam == AgentIdentity.class || businessParam == IdempotencyKey.class) {
        throw new IllegalStateException(String.format(
                "Tool [%s] last parameter must be a business DTO, got %s", name, businessParam.getName()));
    }
}
```

并把 import 加上：

```java
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
```

注意：放开会破坏 Task 3 的 ToolRegistryTest 里的 `voidReturnToolFailsValidation` 测试 —— 因为新校验仍然拒绝 void 返回，所以该测试还会过。

- [ ] **Step 7.4: 实现 AgentRequest / AgentResponse / AgentService**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentRequest.java`

```java
package io.github.yysf1949.rag.agent.api;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;

/**
 * Agent 单次调用的输入。
 *
 * <p>对齐「路条编程」文章 §"查询 ≠ 执行，必须拆开"：
 * {@code toolName} + {@code requestPayload} + {@code idempotencyKey} 三者绑定。</p>
 *
 * @param identity        调用者身份
 * @param toolName        工具名（kebab-case）
 * @param requestPayload  业务请求 DTO（由编排层 JSON 序列化后再反序列化给工具）
 * @param idempotencyKey  幂等键（L2+ 必传；L1 可为 null）
 */
public record AgentRequest(
        AgentIdentity identity,
        String toolName,
        Object requestPayload,
        IdempotencyKey idempotencyKey
) {

    public static AgentRequest of(AgentIdentity identity, String toolName,
                                  Object requestPayload, IdempotencyKey key) {
        return new AgentRequest(identity, toolName, requestPayload, key);
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentResponse.java`

```java
package io.github.yysf1949.rag.agent.api;

/**
 * Agent 单次调用的输出。
 *
 * @param toolName      工具名（与请求一致）
 * @param outcome       SUCCESS / FAILURE / DENIED
 * @param toolResponse  工具返回值（业务侧 DTO）
 * @param message       给用户的解释性文字（模型可选地包一层；本 Phase 直接吐 toolResponse.toString()）
 * @param latencyMs     端到端耗时（含治理层 + 工具本身）
 */
public record AgentResponse(
        String toolName,
        String outcome,
        Object toolResponse,
        String message,
        long latencyMs
) { }
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentService.java`

```java
package io.github.yysf1949.rag.agent.api;

/**
 * 公开 API — 编排层的门面。HTTP / GraphQL / gRPC 层都通过这个接口调 Agent。
 */
public interface AgentService {
    AgentResponse execute(AgentRequest request);
}
```

- [ ] **Step 7.5: 实现 AgentLoop + DefaultAgentLoop**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/AgentLoop.java`

```java
package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;

/**
 * 单次工具调用循环 — 找到 tool → 过风险门 → 调 → 审计。
 *
 * <p>本 Phase 不实现"模型选 tool"那一步 — 由 {@code SpringAiAgentAdapter}
 * 在 LLM 拿到 tool_calls 后调用 {@code AgentLoop.execute}。
 * 真正的"模型迭代循环"（重试、改 tool）由 Spring AI 1.0.9 的
 * {@code FunctionCallingCallback} 处理。</p>
 */
public interface AgentLoop {
    AgentResponse execute(AgentRequest request);
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoop.java`

```java
package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.core.port.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认单次循环 — 找到 tool → 过风险门 → 反射调用 → 审计。
 *
 * <h2>调用约定</h2>
 * <p>工具方法允许 1-3 个参数，按位置传入：</p>
 * <ol>
 *   <li>参数 0: {@code AgentIdentity}（可选，编排层注入）</li>
 *   <li>参数 1: {@code IdempotencyKey}（可选，写操作必传）</li>
 *   <li>参数 2: 业务 DTO（必传）</li>
 * </ol>
 */
@Component
public class DefaultAgentLoop implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);

    private final ToolRegistry registry;
    private final RiskGate riskGate;
    private final IdempotencyStore idempotencyStore;
    private final ToolAuditBridge auditBridge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idempotencyStore, ToolAuditBridge auditBridge) {
        this.registry = registry;
        this.riskGate = riskGate;
        this.idempotencyStore = idempotencyStore;
        this.auditBridge = auditBridge;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long t0 = System.currentTimeMillis();
        ToolDescriptor desc = registry.get(request.toolName());
        try {
            // 风险门控
            riskGate.check(desc, request.identity(), request.idempotencyKey());
        } catch (RuntimeException denied) {
            // 拒绝也写审计
            auditBridge.record(new ToolInvocationContext(
                    request.identity(), request.toolName(),
                    safeToJson(request.requestPayload()),
                    denied.getMessage(),
                    System.currentTimeMillis() - t0, "DENIED"));
            throw denied;
        }

        // 反射调用（按参数类型注入 identity / idemKey / 业务 DTO）
        Object result;
        try {
            result = invokeWithInjection(desc, request);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - t0;
            auditBridge.record(new ToolInvocationContext(
                    request.identity(), request.toolName(),
                    safeToJson(request.requestPayload()),
                    e.getMessage() == null ? "" : e.getMessage(),
                    latency, "FAILURE"));
            throw new RuntimeException("Tool [" + request.toolName() + "] execution failed: " + e.getMessage(), e);
        }

        long latency = System.currentTimeMillis() - t0;
        String resultJson = safeToJson(result);
        auditBridge.record(new ToolInvocationContext(
                request.identity(), request.toolName(),
                safeToJson(request.requestPayload()),
                resultJson,
                latency, "SUCCESS"));

        log.info("Agent tool [{}] completed outcome=SUCCESS latency={}ms",
                request.toolName(), latency);
        return new AgentResponse(request.toolName(), "SUCCESS", result, resultJson, latency);
    }

    private Object invokeWithInjection(ToolDescriptor desc, AgentRequest request) throws Exception {
        Method m = desc.method();
        Class<?>[] params = m.getParameterTypes();
        List<Object> args = new ArrayList<>(3);
        for (Class<?> p : params) {
            if (p == io.github.yysf1949.rag.agent.governance.AgentIdentity.class) {
                args.add(request.identity());
            } else if (p == io.github.yysf1949.rag.agent.governance.IdempotencyKey.class) {
                args.add(request.idempotencyKey());
            } else {
                args.add(request.requestPayload());
            }
        }
        return m.invoke(desc.bean(), args.toArray());
    }

    private String safeToJson(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return o.toString();
        }
    }
}
```

- [ ] **Step 7.6: 实现 SpringAiAgentAdapter**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/SpringAiAgentAdapter.java`

```java
package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 1.0.9 适配器 — 把 {@code ToolDescriptor} 翻译成
 * {@code FunctionCallback} 数组，供 Spring AI 1.0.9 的 ChatClient 调用。
 *
 * <h2>升级路径</h2>
 * <p>Spring AI 2.0 起 {@code @Tool} 注解成为主流 — 本类届时改为
 * 扫描 {@code @Tool} 方法生成 {@code ToolDefinition}（仍是单文件改动）。
 * 业务侧不感知。</p>
 */
@Component
public class SpringAiAgentAdapter {

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiAgentAdapter(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 把所有已注册 Tool 翻译成 Spring AI 1.0.9 的 {@code FunctionCallback}。
     */
    public FunctionCallback[] getFunctionCallbacks() {
        List<FunctionCallback> out = new ArrayList<>();
        for (String name : registry.listNames()) {
            ToolDescriptor desc = registry.get(name);
            out.add(FunctionCallbackWrapper.builder(
                    new SpringAiFunctionImpl(desc, objectMapper))
                    .withName(desc.name())
                    .withDescription(desc.description())
                    .build());
        }
        return out.toArray(new FunctionCallback[0]);
    }

    /**
     * 不走 ChatClient 的直接调用入口 — 单测和后续 e2e test 用。
     */
    public String invoke(String toolName, String requestJson) {
        ToolDescriptor desc = registry.get(toolName);
        try {
            Class<?> reqType = desc.method().getParameterTypes()[0];
            Object req = objectMapper.readValue(requestJson, reqType);
            Object result = desc.invoke(req);
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad request JSON for tool " + toolName, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Tool invocation failed: " + e.getMessage(), e);
        }
    }

    /** Spring AI 1.0.9 {@code FunctionCallback} 需要的 Function impl — 单参数。 */
    private record SpringAiFunctionImpl(ToolDescriptor descriptor, ObjectMapper mapper)
            implements java.util.function.Function<String, String> {

        @Override
        public String apply(String requestJson) {
            ToolDescriptor desc = descriptor;
            try {
                Class<?> reqType = desc.method().getParameterTypes()[0];
                Object req = mapper.readValue(requestJson, reqType);
                Object result = desc.invoke(req);
                return mapper.writeValueAsString(result);
            } catch (Exception e) {
                throw new RuntimeException("Function apply failed: " + e.getMessage(), e);
            }
        }
    }
}
```

- [ ] **Step 7.7: 跑测试确认 PASS**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest='DefaultAgentLoopTest,SpringAiAgentAdapterTest' -q
```

预期: `BUILD SUCCESS`，6 个用例全过。

- [ ] **Step 7.8: Commit**

```bash
git add rag-agent/src
git commit -m "feat(agent): orchestration — AgentLoop + SpringAiAgentAdapter + AgentService (Phase 9 Task 7)"
```

---

## Task 8: HTTP 接入 — `AgentController` + exception handler 扩展

**Files:**
- Modify: `rag-agent/pom.xml` — 加 spring-boot-starter-web 依赖
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/web/AgentController.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/web/AgentExceptionHandler.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/web/AgentControllerTest.java`（用 @WebMvcTest + MockBean）

- [ ] **Step 8.1: 修改 rag-agent/pom.xml 增加 spring-boot-starter-web**

在 `<dependencies>` 块内加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

**为什么 provided**：rag-agent 设计为可在 rag-app 内复用 + 单独部署，HTTP 容器依赖按需注入。provided 模式让 rag-app 启动时自带 Web，但 rag-agent 单测可以 `@WebMvcTest` 拉起来。

- [ ] **Step 8.2: 写 AgentController + ExceptionHandler**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/web/AgentController.java`

```java
package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Agent HTTP 接入 — POST /api/agent/invoke
 *
 * <h2>对齐已有 RAG controller 风格</h2>
 * <p>tenantId 走 header（{@code X-Tenant-Id}），body 里的 tenantId 忽略。
 * 跟 {@code RagController} 一致 — 不破坏设计原则 §6 租户硬墙。</p>
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent", description = "AI Agent Action Layer invocation endpoints.")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    public record InvokeRequest(
            @NotBlank String userId,
            String sessionId,
            @NotBlank String toolName,
            Object payload,
            String idempotencyToken
    ) { }

    @PostMapping("/invoke")
    @Operation(summary = "调用一个 Agent Tool（编排层单次循环）",
            description = "tenantId 走 X-Tenant-Id header；L2+ 写操作需传 idempotencyToken。")
    public ResponseEntity<AgentResponse> invoke(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody InvokeRequest req,
            HttpServletRequest http) {
        AgentIdentity identity = new AgentIdentity(
                tenantId, req.userId(), req.sessionId(), Set.of("user"));
        IdempotencyKey key = req.idempotencyToken() == null ? null :
                IdempotencyKey.of(tenantId, req.userId(), req.sessionId(), req.toolName(), req.idempotencyToken());
        AgentRequest ar = AgentRequest.of(identity, req.toolName(), req.payload(), key);
        return ResponseEntity.ok(agentService.execute(ar));
    }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/web/AgentExceptionHandler.java`

```java
package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.exception.IdempotencyConflictException;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent 异常 → HTTP 状态码映射。
 *
 * <p>对齐项目既有 {@code RagExceptionHandler} 风格（{@code ProblemDetail} + 422/403/404）。</p>
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    @ExceptionHandler(ToolNotFoundException.class)
    public ProblemDetail handleToolNotFound(ToolNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ToolRiskDeniedException.class)
    public ProblemDetail handleRiskDenied(ToolRiskDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
```

- [ ] **Step 8.3: 写 AgentController 单测**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/web/AgentControllerTest.java`

```java
package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AgentService agentService;

    @Test
    void invokeReturns200() throws Exception {
        var kbResp = new KbSearchTool.Response("answer", io.github.yysf1949.rag.core.model.AnswerSource.LLM, java.util.List.of());
        when(agentService.execute(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("kb_search", "SUCCESS", kbResp, "ok", 12L));

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "toolName", "kb_search",
                "payload", Map.of("tenantId", "t1", "userId", "u1", "rawText", "hi")));
        mvc.perform(post("/api/agent/invoke")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("kb_search"))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));
    }

    @Test
    void missingTenantHeaderReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1", "toolName", "kb_search"));
        mvc.perform(post("/api/agent/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8.4: 跑测试**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentControllerTest -q
```

预期: `BUILD SUCCESS`，2 个用例全过。

- [ ] **Step 8.5: Commit**

```bash
git add rag-agent/src rag-agent/pom.xml
git commit -m "feat(agent): web layer — AgentController + AgentExceptionHandler (Phase 9 Task 8)"
```

---

## Task 9: 端到端集成测试（不走 LLM）— `AgentEndToEndTest`

**Files:**
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/e2e/AgentEndToEndTest.java`

- [ ] **Step 9.1: 写 E2E 测试**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/e2e/AgentEndToEndTest.java`

```java
package io.github.yysf1949.rag.agent.e2e;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.builtin.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.orchestration.DefaultAgentLoop;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.core.model.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 端到端冒烟测试 — 不接真实 LLM，走 stub QAService。
 *
 * <h2>覆盖</h2>
 * <ul>
 *   <li>L1 工具（kb_search）成功路径 + 审计</li>
 *   <li>L2 工具（create_reminder_ticket）首次创建 + 同 key 幂等</li>
 *   <li>风险门控：L2 缺 idempotencyKey 拒绝 + 审计 DENIED</li>
 *   <li>租户隔离：tenant2 不能看到 tenant1 的工单</li>
 * </ul>
 */
class AgentEndToEndTest {

    private AgentService agentService;
    private InMemoryTicketRepository ticketRepo;
    private List<String> auditOutcomes;

    @BeforeEach
    void setUp() {
        QAService qa = mock(QAService.class);
        when(qa.answer(any(Query.class))).thenReturn(
                new Answer("退款政策 7 天无理由", AnswerSource.LLM, null, null));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(QAService.class, () -> qa);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, DefaultRiskGate.class);
            ctx.refresh();
            ToolRegistry registry = ctx.getBean(InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
            ticketRepo = ctx.getBean(InMemoryTicketRepository.class);

            auditOutcomes = new ArrayList<>();
            LlmAuditHook hook = (t, u, s, q, m, pt, pb, c, l, o) -> auditOutcomes.add(o);
            ToolAuditBridge bridge = new ToolAuditBridge(hook);
            IdempotencyStore idem = ctx.getBean(InMemoryIdempotencyStore.class);
            RiskGate gate = new DefaultRiskGate();
            agentService = new DefaultAgentLoop(registry, gate, idem, bridge);
        }
    }

    @Test
    void l1KbSearchHappyPath() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var resp = agentService.execute(AgentRequest.of(identity, "kb_search",
                new KbSearchTool.Request("t1", "u1", "怎么退款", Set.of(), 5, null), null));
        assertThat(resp.outcome()).isEqualTo("SUCCESS");
        assertThat(auditOutcomes).contains("SUCCESS");
    }

    @Test
    void l2CreateTicketHappyPath() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-E2E-1");
        var resp = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "kb 返回空结果，请人工"), key));
        assertThat(resp.outcome()).isEqualTo("SUCCESS");
        TicketTool.Ticket t = (TicketTool.Ticket) resp.toolResponse();
        assertThat(t.tenantId()).isEqualTo("t1");
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
    }

    @Test
    void l2IdempotencyReplay() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-E2E-2");
        var r1 = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "first"), key));
        var r2 = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "second"), key));
        assertThat(r1.toolResponse()).isEqualTo(r2.toolResponse());
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
    }

    @Test
    void l2MissingIdempotencyKeyDenied() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        try {
            agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                    new TicketTool.Request("kb-search", "no key"), null));
        } catch (RuntimeException ignored) { }
        assertThat(auditOutcomes).contains("DENIED");
        assertThat(ticketRepo.findByTenant("t1")).isEmpty();
    }

    @Test
    void tenantIsolation() {
        var identity1 = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var identity2 = new AgentIdentity("t2", "u2", "s2", Set.of("user"));
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "iso-1");
        var k2 = IdempotencyKey.of("t2", "u2", "s2", "create_reminder_ticket", "iso-2");
        agentService.execute(AgentRequest.of(identity1, "create_reminder_ticket",
                new TicketTool.Request("kb", "x"), k1));
        agentService.execute(AgentRequest.of(identity2, "create_reminder_ticket",
                new TicketTool.Request("kb", "y"), k2));
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
        assertThat(ticketRepo.findByTenant("t2")).hasSize(1);
    }
}
```

- [ ] **Step 9.2: 跑测试**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentEndToEndTest -q
```

预期: `BUILD SUCCESS`，5 个用例全过。

- [ ] **Step 9.3: Commit**

```bash
git add rag-agent/src
git commit -m "test(agent): E2E smoke test for rag-agent (Phase 9 Task 9)"
```

---

## Task 10: 全部测试套件 + 模块依赖验证

- [ ] **Step 10.1: 跑 rag-agent 全套测试**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q
```

预期: `BUILD SUCCESS`，全部用例（10 个测试类，约 35+ 个用例）全过。

- [ ] **Step 10.2: 跑整个项目全量 build（确认 rag-agent 不破坏其他模块）**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn clean install -DskipTests -q
```

预期: `BUILD SUCCESS`，所有 7 个模块编译通过。

- [ ] **Step 10.3: 跑根项目 test-compile（确保所有模块测试都能编译）**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn test-compile -q
```

预期: `BUILD SUCCESS`。

- [ ] **Step 10.4: 跑 rag-test 已有测试（确保 Phase 8 成果未破坏）**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-test test -q
```

预期: `BUILD SUCCESS`，所有 Phase 8 测试（166 unit tests）仍然通过。

- [ ] **Step 10.5: Commit（如有 compile-only fix）**

```bash
git status
# 如果有未 commit 改动：
# git add -A && git commit -m "build(agent): resolve cross-module compile issues (Phase 9 Task 10)"
```

---

## Task 11: 文档同步 — architecture / design-principles / observability / RUNBOOK

**Files:**
- Modify: `docs/architecture.md` — 增加 §9 Agent Action Layer 章节
- Modify: `docs/design-principles.md` — 增加 §13 工具风险分级原则
- Modify: `docs/observability.md` — 增加 §10 Agent 治理指标
- Modify: `docs/RUNBOOK.md` — 增加 §11 Agent 故障排查
- Modify: `docs/evolution.md` — 增加 Phase 9 roadmap
- Modify: `README.md` — 增加 rag-agent 模块说明

- [ ] **Step 11.1: 修改 architecture.md 增加 Agent Layer 章节**

在 `## 8. 已知不足` 之前插入：

```markdown
## 9. Agent Action Layer (Phase 9)

> **新增模块**: `rag-agent` — 把企业后端 Service 改造成 AI Agent 可调用的工具集。

### 9.1 三层架构

| 层 | 子包 | 职责 |
|---|---|---|
| 编排层 | `orchestration/` | 意图理解 + 工具选择 + 调用循环；`SpringAiAgentAdapter` 桥接 Spring AI 1.0.9 `FunctionCallingCallback` |
| 动作层 | `action/` | `@ToolSpec` + `ToolRegistry` + 4 级风险分级 |
| 治理层 | `governance/` | 身份 + 幂等 + 风险门控 + 审计（桥接到现有 `LlmAuditHook`） |

### 9.2 4 级工具风险

| 级别 | 示例 | 自动执行 | 幂等键 |
|---|---|---|---|
| L1_READ | `kb_search` | ✅ | 不需要 |
| L2_REVERSIBLE | `create_reminder_ticket` | ✅ | 强制 |
| L3_BUSINESS_STATE | （未来：创建退款） | ⚠️ 二次确认 | 强制 |
| L4_HIGH_RISK | （未来：删除 KB） | ❌ 需 admin | 强制 |

### 9.3 升级路径

当前使用 Spring AI 1.0.9 `FunctionCallingCallback`。业务侧只依赖
`ToolDescriptor` 抽象层，升级 2.0 `@Tool` 时只改 `SpringAiAgentAdapter` 一个文件。

参考文章: 「路条编程」《Salesforce 36 亿美元押注 AI 客服》(2026-06-17)
```

- [ ] **Step 11.2: 修改 design-principles.md 增加 §13**

在最后追加：

```markdown
## 13. 工具风险分级 (Phase 9 新增)

**原则**: 每个 Agent 可调用的工具必须明确标注风险级别（L1-L4），治理层强制门控。

| 级别 | 治理要求 |
|---|---|
| L1 | 自动放行 |
| L2 | idempotencyKey 强制 |
| L3 | idempotencyKey 强制 + 用户二次确认 |
| L4 | idempotencyKey 强制 + admin 角色 |

**反模式**: 把"查询"和"修改"做成同一个大工具 — 模型选错参数就把查询变写操作。

参考「路条编程」AI 客服文章 §"查询 ≠ 执行，必须拆开"。
```

- [ ] **Step 11.3: 修改 observability.md 增加 §10**

在 §9 之后插入：

```markdown
## 10. Agent 治理指标 (Phase 9)

| 指标 | 类型 | 说明 |
|---|---|---|
| `rag_agent_tool_invocations_total{tool,outcome}` | Counter | 工具调用次数（SUCCESS/FAILURE/DENIED） |
| `rag_agent_tool_latency_ms{tool}` | Timer | 端到端延迟（含治理层） |
| `rag_agent_idempotency_replays_total{tool}` | Counter | 幂等回放次数 |
| `rag_agent_risk_denied_total{tool,level}` | Counter | 风险门控拒绝次数 |
| `rag.audit.errors.total` (已存在) | Gauge | 审计通道错误 |

**审计日志格式**（复用 `LlmAuditHook`）:
```
audit: {"timestamp":"...","type":"AGENT_TOOL_CALL","tenantId":"t1","actorId":"u1",
"resourceId":"kb_search","outcome":"SUCCESS","fields":{"latencyMs":12,"queryHash":"..."}}
```
```

- [ ] **Step 11.4: 修改 RUNBOOK.md 增加 §11**

```markdown
## 11. Agent Action Layer 故障排查 (Phase 9)

### 11.1 工具找不到 (404)

- 检查 `InMemoryToolRegistry` 启动日志：应看到 `Registered tool [xxx]`
- 检查 `@ToolSpec.name` 是否唯一 — 重复会 `IllegalStateException`

### 11.2 风险门控拒绝 (403)

- 看 `rag_agent_risk_denied_total` 指标，按 `level` 分桶
- L2+ 工具必须传 `idempotencyToken`，否则 DENIED
- L4 必须有 `admin` 角色

### 11.3 幂等回放异常

- 看 `rag_agent_idempotency_replays_total` — 突增说明上游有重试
- 检查 `IdempotencyStore` 实现（InMemory 单实例；分布式需换 Redis）

### 11.4 审计失败

- 看 `rag.audit.errors.total` gauge
- 检查 `AuditChannel` appender 配置（90 天 RollingFile）
```

- [ ] **Step 11.5: 修改 evolution.md**

在 Phase 8 之后追加：

```markdown
## Phase 9 — Agent Action Layer (进行中)

- **新模块**: `rag-agent`
- **核心**: 3 层架构（编排/动作/治理）+ 4 级工具风险分级
- **参考**: 「路条编程」AI 客服文章 (2026-06-17)
- **未做**: 真实 LLM 接入 + Spring AI 2.0 升级 + 多 Agent 协作
```

- [ ] **Step 11.6: 修改 README.md**

在"模块结构"小节增加：

```markdown
- **rag-agent** (Phase 9+) — Agent Action Layer，把企业后端 Service 改造成 AI Agent 可调用的工具集
```

- [ ] **Step 11.7: 跑 markdown lint（如有）**

```bash
cd ~/projects/spring-ai-alibaba-rag
ls scripts/
# 如果有 markdown 检查脚本就跑一下
```

- [ ] **Step 11.8: Commit**

```bash
git add docs/ README.md
git commit -m "docs(agent): architecture + design principles + observability + RUNBOOK (Phase 9 Task 11)"
```

---

## Task 12: 端到端真实环境 smoke + git push

- [ ] **Step 12.1: 启动 rag-app 验证 Agent endpoint 真实注册**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-app -am spring-boot:run -Dspring-boot.run.profiles=dev > /tmp/rag-app.log 2>&1 &
APP_PID=$!
echo "PID=$APP_PID"
# 等启动
for i in {1..30}; do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "app up after ${i}s"
    break
  fi
  sleep 1
done
```

⚠️ **注意**: 本任务需要先把 `rag-agent` 加到 `rag-app/pom.xml` 作为依赖，否则 `/api/agent/invoke` 不会注册。**这是 Task 12 的先决条件，必须在 Step 12.0 完成**：

- [ ] **Step 12.0 (前置): 把 rag-agent 加到 rag-app/pom.xml**

修改 `rag-app/pom.xml`，在 `<dependencies>` 内增加：

```xml
<dependency>
    <groupId>io.github.yysf1949.rag</groupId>
    <artifactId>rag-agent</artifactId>
    <version>${project.version}</version>
</dependency>
```

```bash
git add rag-app/pom.xml
git commit -m "build(app): add rag-agent dependency (Phase 9 Task 12.0)"
```

- [ ] **Step 12.2: 验证 endpoint 真存在**

```bash
# Agent L1 工具（kb_search）
curl -s -X POST http://localhost:8080/api/agent/invoke \
  -H "X-Tenant-Id: tenant1" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"kb_search","payload":{"tenantId":"tenant1","userId":"u1","rawText":"怎么退款","topK":5}}' | head -c 500
echo ""

# Agent L2 工具（create_reminder_ticket）— 第一次
curl -s -X POST http://localhost:8080/api/agent/invoke \
  -H "X-Tenant-Id: tenant1" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"create_reminder_ticket","payload":{"sourceTool":"kb-search","description":"人工跟进"},"idempotencyToken":"e2e-token-1"}' | head -c 500
echo ""

# 第二次同 token → 应返回相同 ticket id
curl -s -X POST http://localhost:8080/api/agent/invoke \
  -H "X-Tenant-Id: tenant1" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"create_reminder_ticket","payload":{"sourceTool":"kb-search","description":"second"},"idempotencyToken":"e2e-token-1"}' | head -c 500
echo ""

# L2 缺 token → 403
curl -s -i -X POST http://localhost:8080/api/agent/invoke \
  -H "X-Tenant-Id: tenant1" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"create_reminder_ticket","payload":{"sourceTool":"kb-search","description":"no key"}}' | head -c 500
```

预期:
- L1 返回 200 + AnswerSource
- L2 第一次返回 200 + ticketId
- L2 第二次返回 200 + **同 ticketId**（幂等生效）
- L2 缺 token 返回 403 + DENIED

- [ ] **Step 12.3: 看审计日志**

```bash
# 找 audit 日志（项目用 RollingFileAppender 90 天）
find ~/projects/spring-ai-alibaba-rag/logs -name "*audit*" 2>/dev/null | head -3
tail -20 <audit_log_path>  # 应看到 agent:kb_search / agent:create_reminder_ticket 等条目
```

- [ ] **Step 12.4: 停掉 app**

```bash
kill $APP_PID 2>/dev/null
sleep 2
ps -p $APP_PID 2>/dev/null && echo "still running" || echo "stopped"
```

- [ ] **Step 12.5: 双写交付 — 复制 plan 到 Obsidian**

```bash
mkdir -p ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/
cp ~/projects/spring-ai-alibaba-rag/docs/superpowers/plans/2026-06-18-agent-action-layer.md \
   ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/
ls -la ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/
```

- [ ] **Step 12.6: 验证远端状态**

```bash
cd ~/projects/spring-ai-alibaba-rag
git status
git log --oneline -10
git ls-remote origin feature/agent-action-layer | head -3
```

如果 feature/agent-action-layer 还没 push：

```bash
git push -u origin feature/agent-action-layer
```

- [ ] **Step 12.7: Commit (如 Step 12.5 改了 doc)**

```bash
git add docs/superpowers/plans/2026-06-18-agent-action-layer.md
git commit -m "docs(plan): Phase 9 plan sync from Obsidian (or vice versa)" 2>&1 | tail -3
git push origin feature/agent-action-layer
```

- [ ] **Step 12.8: 总结**

更新 `docs/LESSONS.md` 追加 Phase 9 章节（可选，留 Phase 10）。

---

## 验收 DoD

Phase 9 完成时必须全部满足：

- [ ] rag-agent 模块独立编译，**不破坏** rag-core / rag-pipeline / rag-redis / rag-app 现有任何代码
- [ ] `mvn -pl rag-agent test` 全过（10+ 测试类，35+ 用例）
- [ ] `mvn clean install -DskipTests` 7 模块全编译通过
- [ ] `mvn -pl rag-test test` 既有 166 测试仍然全过（无回归）
- [ ] `POST /api/agent/invoke` 在真实 rag-app 启动后注册，curl 验证 L1/L2/幂等/拒绝 4 种路径
- [ ] 审计日志能看到 `agent:kb_search` / `agent:create_reminder_ticket` 条目
- [ ] 文档（architecture / design-principles / observability / RUNBOOK / README）已同步
- [ ] plan + LESSONS 双写到 Obsidian
- [ ] `git push -u origin feature/agent-action-layer` 成功
- [ ] `git ls-remote origin feature/agent-action-layer` HEAD == 本地 HEAD

---

## 与既有 Phase 的关系

- **Phase 8** (3545b06) — AuditChannel / Redis SSL / C9.2 metrics；本 Phase **复用** LlmAuditHook 通道，**不**新建
- **Phase 7** — QAService 8-step chain；KbSearchTool 包装 QAService（不重复实现检索）
- **Phase 5-P4** — SiliconFlow 切换；本 Phase 默认走 Stub QA，**未来**接 SiliconFlow 只需换 EmbeddingGateway / LlmService 实现
- **未做**（明确不在本 Phase 范围）:
  - Spring AI 2.0 升级（独立 Phase 10+）
  - 真实 LLM 接入（用 stub 走通流程）
  - 多 Agent 协作 / multi-turn conversation
  - 工单系统的真实持久化（InMemory 仅 demo）
  - L3/L4 工具的"用户二次确认" UI/工作流（后端 API 已支持，UI 留 Phase 10）
  - OpenTelemetry 分布式追踪

---

## 已知风险与缓解

| 风险 | 缓解 |
|---|---|
| 工具方法参数注入歧义（identity / idemKey 顺序） | `DefaultAgentLoop.invokeWithInjection` 按参数类型匹配，未来如扩展更多治理参数需调整 |
| 反射调用性能 | 工具数 ≤ 100 时 JVM JIT 优化足够；未来上千可换 MethodHandle |
| IdempotencyStore 单实例限制 | 文档明确；Phase 10 切 Redis |
| 真实 LLM 选 tool 的循环无真实测试 | 保留 `SpringAiAgentAdapter` 单测 + e2e 走 stub；真实 SiliconFlow 集成留 Phase 10 |
| ToolDescriptor.validate 放宽可能放过错误工具签名 | 10 个测试类覆盖正常 + 异常路径 |

---

**End of Plan — Phase 9: Agent Action Layer**
