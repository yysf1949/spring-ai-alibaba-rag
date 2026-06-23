# Phase 10 — Agent Action Layer Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 9 已 ship 的 `rag-agent` 基础上，按「路条编程」《Salesforce 36 亿美元押注 AI 客服》文章方法论的**全部**核心论点落地 — 补齐 P0（真实业务工具 + 人工转接 + 4 级风险分流）、P1（指标体系 + 持久化幂等 + 限流）、P2（多渠道 + 业务 Service 复用）、P3（文档 + 跨仓库回归），让项目从"骨架 + 最小 demo"升级为"生产级 AI 客服 Action Layer"。

**Architecture:** 在 Phase 9 的 3 层架构（编排/动作/治理）上扩展，**不重写**：
- **新增业务工具集**（订单/退款/优惠券/物流）走 `rag-pipeline` 已有的 `QAService` 模式，每个工具都是 `@ToolSpec` 注解的 Spring bean；
- **新增 `HandoffService`**（人工转接层）独立模块 `rag-agent/handoff/`，复用治理层 `ToolInvocationContext` 打包"Agent 摘要 → 人工客服"；
- **新增 `AgentMetrics`** 治理层组件，发 4 类 Micrometer 指标到现有 Prom 端口；
- **`IdempotencyStore` 加 Redis 实现**（`rag-agent/governance/RedisIdempotencyStore`）— 通过 `JedisClient` 复用 `rag-redis` 已有的连接池；
- **新增 `ChannelAdapter` 接口**（多渠道接入层）+ `WechatChannelAdapter` demo（HTTP POST / 微信回调），统一封装为 `AgentRequest`；
- **限流**用 Resilience4j `@RateLimiter`（依赖已存在），配置在 `application.yml`。

**Tech Stack:**
- 继承 Phase 9 全部：Spring Boot 3.3.5 / Java 21 / Spring AI 1.0.9 / Resilience4j 2.2.0 / Micrometer 1.14.x
- 新增：Jedis（`rag-redis` 已用）+ spring-data-redis（可选）
- 业务工具：复用 `rag-pipeline` 现有 `QAService` + 新增的 `OrderPort`/`RefundPort` port 接口（port-and-adapter）

**参考文章**：「路条编程」《Salesforce 36 亿美元押注 AI 客服：Java 后端真正的机会，不是接个大模型》(2026-06-17)，归档 `~/ObsidianVault/AI研究/spring-ai-alibaba-rag/lessons-summary/2026-06-18-公众号-AI客服Action-Layer-路条编程.md`。

**前置依赖**: Phase 9 `08ee734` 已 ship + push，远端 `feature/agent-action-layer` 分支领先 main 15 commits。

---

## 既有约束（设计时必须遵守，继承 Phase 9）

1. **`rag-core` 是 leaf 模块**：禁止 Spring / Spring AI / Redis 依赖。Phase 10 不改 `rag-core`。
2. **`rag-pipeline` 业务不变**：Phase 10 新增业务工具**走现有 port 接口**（如 `QAService`），不直接调 `QAServiceImpl`。
3. **审计走 `LlmAuditHook` 复用**：业务工具调用也走 `ToolAuditBridge` → `LlmAuditHook`，不新建平行管道。
4. **租户硬墙**：`tenantId` 永不跨用户。
5. **Spring AI 1.0.9 保持不升级**：`ToolDescriptor` 抽象层 + `SpringAiAgentAdapter` 不动。
6. **TDD 红绿循环**：每个 Step 先写测试，再实现。
7. **Surgical 变更**：Phase 10 不重写 Phase 9 代码，所有 Phase 9 测试继续通过。
8. **frequent commit**：每个 Task 独立 commit，每 Step 跑完测试后 commit。

---

## 文件结构（Phase 10 新增/修改）

```
spring-ai-alibaba-rag/
├── pom.xml                                  # Modify: <module> 无变化
├── rag-agent/                               # 大量新增
│   ├── pom.xml                              # Modify: 加 spring-data-redis (test, Jedis 已有)
│   └── src/
│       ├── main/
│       │   ├── java/io/github/yysf1949/rag/agent/
│       │   │   ├── package-info.java
│       │   │   ├── api/                     # 扩展
│       │   │   │   ├── AgentRequest.java            # Modify: 加 channel / dryRun 字段
│       │   │   │   ├── AgentResponse.java           # Modify: 加 outcome 枚举
│       │   │   │   ├── AgentService.java            # 不变
│       │   │   │   ├── AgentOutcome.java            # NEW: outcome enum (SUCCESS/FAILURE/DENIED/REPLAY/HANDOFF_REQUIRED)
│       │   │   │   ├── AgentChannel.java            # NEW: 渠道枚举 (HTTP/WECHAT/EMAIL/APP)
│       │   │   │   └── ChannelAdapter.java          # NEW: 多渠道适配器接口
│       │   │   ├── action/                  # 扩展
│       │   │   │   ├── RiskLevel.java               # 不变
│       │   │   │   ├── ToolSpec.java                # 不变
│       │   │   │   ├── ToolDescriptor.java          # Modify: 加 maxAmountCents 字段（L3 金额门控）
│       │   │   │   ├── ToolRegistry.java            # 不变
│       │   │   │   └── InMemoryToolRegistry.java    # 不变
│       │   │   ├── governance/              # 扩展
│       │   │   │   ├── AgentIdentity.java           # Modify: 加 channel 字段
│       │   │   │   ├── IdempotencyStore.java        # Modify: 加 default TTL 配置入口
│       │   │   │   ├── InMemoryIdempotencyStore.java# 不变
│       │   │   │   ├── RedisIdempotencyStore.java   # NEW: 走 Jedis SETNX + EXPIRE
│       │   │   │   ├── ToolInvocationContext.java   # 不变
│       │   │   │   ├── RiskGate.java                # Modify: 加 maxAmount 校验
│       │   │   │   ├── DefaultRiskGate.java         # Modify: 加 L3 金额门控 + L4 admin 角色
│       │   │   │   ├── ToolAuditBridge.java         # 不变
│       │   │   │   ├── AgentMetrics.java            # NEW: Micrometer 4 指标
│       │   │   │   └── AgentRateLimiter.java        # NEW: 工具级限流 (@RateLimiter wrapper)
│       │   │   ├── orchestration/           # 不变
│       │   │   │   ├── AgentLoop.java               # 不变
│       │   │   │   ├── DefaultAgentLoop.java        # Modify: 加 metrics 埋点 + handoff 分流
│       │   │   │   └── SpringAiAgentAdapter.java    # 不变
│       │   │   ├── builtin/                 # 大幅扩展
│       │   │   │   ├── KbSearchTool.java            # 不变
│       │   │   │   ├── TicketTool.java              # 不变
│       │   │   │   ├── InMemoryTicketRepository.java# 不变
│       │   │   │   ├── OrderTool.java               # NEW: 订单查询/取消 (L1 + L3)
│       │   │   │   ├── RefundTool.java              # NEW: 创建退款申请 (L3) + 审批通过退款 (L4)
│       │   │   │   ├── CouponTool.java              # NEW: 补发优惠券 (L3)
│       │   │   │   ├── LogisticsTool.java           # NEW: 物流查询 (L1)
│       │   │   │   ├── OrderRepository.java         # NEW: 内存版订单仓储 (mock 业务 Service)
│       │   │   │   ├── RefundRepository.java        # NEW: 内存版退款仓储
│       │   │   │   └── CouponRepository.java        # NEW: 内存版优惠券仓储
│       │   │   ├── handoff/                 # NEW package
│       │   │   │   ├── HandoffService.java          # 转人工服务
│       │   │   │   ├── HandoffContext.java          # 转接上下文 record
│       │   │   │   ├── HandoffReason.java           # 触发原因 enum
│       │   │   │   ├── HandoffChannel.java          # 转接渠道 enum (WORK_ORDER/LIVE_CHAT/PHONE)
│       │   │   │   ├── HumanReviewQueue.java        # 内存版待处理队列
│       │   │   │   └── HandoffExceptionHandler.java # 异常处理
│       │   │   ├── channel/                 # NEW package
│       │   │   │   ├── HttpChannelAdapter.java      # 现有 AgentController 包成 adapter
│       │   │   │   └── WechatChannelAdapter.java    # 微信回调 demo (POST /api/agent/wechat)
│       │   │   ├── config/
│       │   │   │   └── AgentAutoConfiguration.java  # Modify: 加 @Bean ChannelAdapter
│       │   │   └── exception/               # 扩展
│       │   │       ├── ToolNotFoundException.java   # 不变
│       │   │       ├── ToolRiskDeniedException.java # 不变
│       │   │       ├── IdempotencyConflictException.java # 不变
│       │   │       ├── HandoffRequiredException.java    # NEW
│       │   │       └── AmountLimitExceededException.java # NEW
│       │   └── resources/                   # NEW
│       │       └── application.yml                 # RateLimiter / Metrics 配置
│       └── test/                            # 大幅扩展
│           ├── java/io/github/yysf1949/rag/agent/
│           │   ├── action/                  
│           │   │   └── ToolDescriptorTest.java      # NEW: maxAmountCents 字段
│           │   ├── governance/              
│           │   │   ├── DefaultRiskGateTest.java     # Modify: 加 L3 金额 + L4 admin
│           │   │   ├── RedisIdempotencyStoreTest.java # NEW (用 @MockBean JedisPool)
│           │   │   └── AgentMetricsTest.java        # NEW
│           │   ├── handoff/                 # NEW test dir
│           │   │   ├── HandoffServiceTest.java
│           │   │   └── HumanReviewQueueTest.java
│           │   ├── channel/                 # NEW test dir
│           │   │   └── WechatChannelAdapterTest.java
│           │   ├── builtin/                 # 大幅扩展
│           │   │   ├── OrderToolTest.java           # NEW
│           │   │   ├── RefundToolTest.java          # NEW
│           │   │   ├── CouponToolTest.java          # NEW
│           │   │   └── LogisticsToolTest.java       # NEW
│           │   └── e2e/
│           │       ├── AgentEndToEndTest.java       # Modify: 加 L3/L4/handoff 用例
│           │       └── AgentChannelEndToEndTest.java # NEW
│           └── resources/                   # NEW
│               └── application-test.yml             # 测试用 profile
├── rag-app/                                 # 少量 Modify
│   ├── pom.xml                              # Modify: 已加 rag-agent, 无变化
│   └── src/main/java/io/github/yysf1949/rag/app/web/
│       └── AgentController.java              # Modify: 走 ChannelAdapter 统一入口
├── docs/                                    # 大幅更新
│   ├── architecture.md                      # Modify: §10 渠道接入层扩展 + §11 多渠道
│   ├── design-principles.md                 # Modify: §15 多渠道 + §16 评估
│   ├── observability.md                     # Modify: §11 Agent 4 大指标
│   ├── RUNBOOK.md                           # Modify: §12 转人工故障排查
│   ├── evolution.md                         # Modify: Phase 10 完成
│   └── superpowers/
│       └── plans/2026-06-18-phase-10-agent-upgrade.md  # 本文档
└── README.md                                # Modify: Phase 10 模块表
```

**总变化估算**: ~40 新文件，~15 修改文件，~3500 行新代码，~1500 行测试，~200 行文档。

---

## 任务优先级与依赖图

```
[Task 1: AgentOutcome 枚举 + AgentChannel 枚举 + AgentRequest 扩展] ← 一切基础
    ↓
[Task 2: AgentMetrics Micrometer 4 指标]                            ← 后续 Task 都用它
    ↓
[Task 3: DefaultRiskGate 加 L3 金额门控 + L4 admin 校验]            ← P0 基础
    ↓
[Task 4: OrderTool (L1 查询 + L3 取消) + OrderRepository]          ← P0.1
    ↓
[Task 5: RefundTool (L3 创建申请 + L4 直接退款) + RefundRepository] ← P0.2
    ↓
[Task 6: CouponTool (L3 补发) + CouponRepository]                   ← P0.1
    ↓
[Task 7: LogisticsTool (L1 物流查询)]                              ← P0.1
    ↓
[Task 8: HandoffService + HandoffContext + 异常 + 队列]            ← P0.3
    ↓
[Task 9: DefaultAgentLoop 接入 metrics + handoff 分流]              ← P0.4
    ↓
[Task 10: RedisIdempotencyStore (走 Jedis SETNX+EXPIRE)]           ← P1.1
    ↓
[Task 11: AgentRateLimiter 工具级限流 (@RateLimiter wrapper)]     ← P1.3
    ↓
[Task 12: ChannelAdapter interface + HttpChannelAdapter + WechatChannelAdapter demo] ← P2.1 + P2.2
    ↓
[Task 13: 文档同步 (architecture/principles/observability/RUNBOOK/evolution/README)] ← P3.1
    ↓
[Task 14: 端到端 smoke (curl 6 场景) + 全仓库 320+ 测试回归]         ← P3.2
    ↓
[Task 15: 双写 plan 到 Obsidian + commit + push + ls-remote verify]  ← 收口
```

**关键依赖**: Task 1 → 2 → 3 → 4-7 → 8 → 9 → 10-12 → 13-15。
**可并行**: Task 4/5/6/7 内部独立（都是 builtin 工具），可串行也可并发 dispatch subagent。

---

## Task 1: AgentOutcome 枚举 + AgentChannel 枚举 + AgentRequest 扩展

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentOutcome.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentChannel.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentRequest.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentResponse.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/api/AgentOutcomeTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/api/AgentChannelTest.java`

- [ ] **Step 1.1: 写 AgentOutcome 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/api/AgentOutcomeTest.java`

```java
package io.github.yysf1949.rag.agent.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOutcomeTest {

    @Test
    void includesFiveStates() {
        assertThat(AgentOutcome.values())
                .containsExactly(
                        AgentOutcome.SUCCESS,
                        AgentOutcome.FAILURE,
                        AgentOutcome.DENIED,
                        AgentOutcome.REPLAY,
                        AgentOutcome.HANDOFF_REQUIRED);
    }

    @Test
    void successAndReplayAreTerminal() {
        assertThat(AgentOutcome.SUCCESS.isTerminal()).isTrue();
        assertThat(AgentOutcome.REPLAY.isTerminal()).isTrue();
        assertThat(AgentOutcome.FAILURE.isTerminal()).isTrue();
        assertThat(AgentOutcome.DENIED.isTerminal()).isTrue();
    }

    @Test
    void handoffRequiredIsNotTerminal() {
        // HANDOFF_REQUIRED 触发后续人工处理，Agent 自身不算终止
        assertThat(AgentOutcome.HANDOFF_REQUIRED.isTerminal()).isFalse();
    }

    @Test
    void parsesFromString() {
        assertThat(AgentOutcome.fromString("SUCCESS")).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(AgentOutcome.fromString("handoff_required")).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThatThrownBy(() -> AgentOutcome.fromString("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
```

- [ ] **Step 1.2: 运行测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentOutcomeTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — `AgentOutcome` 类找不到（编译错误）。

- [ ] **Step 1.3: 实现 AgentOutcome enum**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentOutcome.java`

```java
package io.github.yysf1949.rag.agent.api;

/**
 * Agent 调用的最终状态。
 *
 * <h2>状态分类</h2>
 * <ul>
 *   <li><b>终态</b>（isTerminal=true）：Agent 这一轮不再继续处理</li>
 *   <ul>
 *     <li>{@link #SUCCESS} — 工具执行成功</li>
 *     <li>{@link #FAILURE} — 工具执行失败（异常/超时）</li>
 *     <li>{@link #DENIED} — 风险门控拒绝（L2+ 缺幂等键/L4 非 admin 等）</li>
 *     <li>{@link #REPLAY} — 幂等键命中，复用上次结果</li>
 *   </ul>
 *   <li><b>非终态</b>（isTerminal=false）：需要后续步骤</li>
 *   <ul>
 *     <li>{@link #HANDOFF_REQUIRED} — Agent 转人工（人工接着处理）</li>
 *   </ul>
 * </ul>
 *
 * <h2>与文章的对应</h2>
 * <p>对齐「路条编程」AI 客服文章 §"人工确认不是失败" — 转人工不是 Agent 失败，
 * 而是"自动处理低风险任务，辅助处理高风险任务"分工的一部分。</p>
 */
public enum AgentOutcome {
    SUCCESS(true),
    FAILURE(true),
    DENIED(true),
    REPLAY(true),
    HANDOFF_REQUIRED(false);

    private final boolean terminal;

    AgentOutcome(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** 容错解析 — 接受 SUCCESS / success / SUCCESS_OR_xxx（取前缀匹配） */
    public static AgentOutcome fromString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("outcome string is null");
        }
        String normalized = s.toUpperCase().replace('-', '_');
        for (AgentOutcome o : values()) {
            if (o.name().equals(normalized) || normalized.startsWith(o.name() + "_")) {
                return o;
            }
        }
        throw new IllegalArgumentException("Unknown AgentOutcome: " + s);
    }
}
```

- [ ] **Step 1.4: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentOutcomeTest 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

- [ ] **Step 1.5: 写 AgentChannel 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/api/AgentChannelTest.java`

```java
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
```

- [ ] **Step 1.6: 运行测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentChannelTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — `AgentChannel` 类找不到。

- [ ] **Step 1.7: 实现 AgentChannel enum**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentChannel.java`

```java
package io.github.yysf1949.rag.agent.api;

/**
 * Agent 请求来源渠道 — 对齐「路条编程」AI 客服文章 5 层架构的"渠道接入层"。
 *
 * <h2>多渠道支持</h2>
 * <p>当前实现 HTTP / 微信 / 邮件 / APP 四种入口，统一封装为 {@link AgentRequest}。
 * 业务侧不感知渠道差异，由 {@code ChannelAdapter} 适配。</p>
 */
public enum AgentChannel {
    /** Web 后台 / API 调用方 — 主要是 B 端或 H5 */
    HTTP(true),
    /** 微信公众号 / 企业微信 — 微信生态客服 */
    WECHAT(true),
    /** 邮件工单入口 — 异步处理 */
    EMAIL(true),
    /** 移动 App 内嵌 SDK */
    APP(true);

    private final boolean humanFacing;

    AgentChannel(boolean humanFacing) {
        this.humanFacing = humanFacing;
    }

    /** 是否由真人触发（true）vs 系统触发（false — 留给后续 Phase） */
    public boolean isHumanFacing() {
        return humanFacing;
    }

    /**
     * 从 HTTP header 解析渠道 — 兼容不同命名风格。
     *
     * @param header X-Channel 值（可为 null）
     * @return 解析结果，null 或未知值默认 HTTP
     */
    public static AgentChannel fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return HTTP;
        }
        String normalized = header.toUpperCase().replace('-', '_').split("[-_]")[0];
        for (AgentChannel c : values()) {
            if (c.name().equals(normalized)) {
                return c;
            }
        }
        return HTTP; // 未知值兜底
    }
}
```

- [ ] **Step 1.8: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentChannelTest 2>&1 | tail -10
```
Expected: `Tests run: 3, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 1.9: 扩展 AgentRequest 加 channel 字段**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentRequest.java`：

把整个 record 替换为：

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
 * <p>Phase 10 新增 {@code channel} 字段 — 渠道接入层（5 层架构 §1）的标识，
 * 用于审计、限流分流、消息模板渲染。</p>
 *
 * @param identity        调用者身份
 * @param toolName        工具名（kebab-case）
 * @param requestPayload  业务请求 DTO（由编排层 JSON 序列化后再反序列化给工具）
 * @param idempotencyKey  幂等键（L2+ 必传；L1 可为 null）
 * @param channel         请求来源渠道（默认 HTTP）
 * @param dryRun          dry-run 模式：true 时所有写操作不真正执行，仅走治理层校验（默认 false）
 */
public record AgentRequest(
        AgentIdentity identity,
        String toolName,
        Object requestPayload,
        IdempotencyKey idempotencyKey,
        AgentChannel channel,
        boolean dryRun
) {

    public static AgentRequest of(AgentIdentity identity, String toolName,
                                  Object requestPayload, IdempotencyKey key) {
        return new AgentRequest(identity, toolName, requestPayload, key,
                AgentChannel.HTTP, false);
    }
}
```

- [ ] **Step 1.10: 扩展 AgentResponse 加 outcome 枚举**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentResponse.java`：

把整个 record 替换为：

```java
package io.github.yysf1949.rag.agent.api;

/**
 * Agent 单次调用的输出。
 *
 * <p>Phase 10 改造：{@code outcome} 字段从 String 升级为 {@link AgentOutcome} enum，
 * 添加 {@code handoffContext} 字段（HANDOFF_REQUIRED 时携带待人工处理的上下文）。</p>
 *
 * @param toolName        工具名（与请求一致）
 * @param outcome         终态 / 非终态
 * @param toolResponse    工具返回值（业务侧 DTO）
 * @param message         给用户的解释性文字
 * @param latencyMs       端到端耗时
 * @param handoffContext  转人工上下文（HANDOFF_REQUIRED 时非空，含已查信息/规则匹配结果）
 */
public record AgentResponse(
        String toolName,
        AgentOutcome outcome,
        Object toolResponse,
        String message,
        long latencyMs,
        HandoffContextPayload handoffContext
) {

    public record HandoffContextPayload(
            String reason,
            String nextChannel,
            String summary,
            String toolChainJson
    ) { }
}
```

注：此处先引用 `HandoffContextPayload` 内嵌 record，**完整 `HandoffContext` 会在 Task 8 实现**。本 Task 仅引入占位 record 满足类型引用。

- [ ] **Step 1.11: 跑全套测试确认不破坏 Phase 9**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -10
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: 之前 Phase 9 的 39 个 + 这次 7 个 = **46 tests pass**。

- [ ] **Step 1.12: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src
git commit -m "feat(agent): AgentOutcome + AgentChannel enums + AgentRequest/Response channel/dryRun (Phase 10 Task 1)

Phase 10 基础设施 — 5 个 outcome 状态（终态 4 + 转人工 1）支撑 P0.3 人工转接；
4 个渠道 enum 支撑 P2 多渠道接入。AgentRequest 加 channel + dryRun 字段；
AgentResponse 升级 outcome 为 enum + 加 handoffContext 字段。

为保证 Phase 9 的 AgentController 不破坏，HandoffContextPayload 用内嵌 record
占位（Task 8 才有完整 HandoffContext 服务）。39+7=46 tests pass."
```

---

## Task 2: AgentMetrics Micrometer 4 指标

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentMetrics.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/AgentMetricsTest.java`
- Modify: `rag-agent/pom.xml` (加 micrometer-registry-prometheus 依赖)

- [ ] **Step 2.1: 在 pom.xml 加 micrometer-registry-prometheus 依赖**

Modify `rag-agent/pom.xml`，在 `<dependencies>` 内 `<dependency><artifactId>micrometer-core</artifactId></dependency>` **之后**追加：

```xml
        <!-- Phase 10: Prometheus 指标导出（4 类 Agent 指标走 /actuator/prometheus） -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <scope>provided</scope>
        </dependency>
```

(provided 跟 spring-boot-starter-web 一致 — rag-app 已带，rag-agent 单测用 SimpleMeterRegistry)

- [ ] **Step 2.2: 写 AgentMetrics 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/AgentMetricsTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private MeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void recordsToolInvocationCounter() {
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 42L);
        double count = registry.counter("agent.tool.invocations",
                "tool", "kb_search", "outcome", "SUCCESS").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordsToolLatencyTimer() {
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 100L);
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 200L);
        long count = registry.timer("agent.tool.latency",
                "tool", "kb_search").count();
        assertThat(count).isEqualTo(2L);
        assertThat(registry.timer("agent.tool.latency",
                "tool", "kb_search").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(300.0);
    }

    @Test
    void recordsHandoffCounter() {
        metrics.recordHandoff("create_refund", "amount_exceeded", "WORK_ORDER");
        assertThat(registry.counter("agent.handoffs",
                "tool", "create_refund", "reason", "amount_exceeded",
                "channel", "WORK_ORDER").count()).isEqualTo(1.0);
    }

    @Test
    void recordsIdempotencyReplays() {
        metrics.recordIdempotencyReplay("create_reminder_ticket");
        metrics.recordIdempotencyReplay("create_reminder_ticket");
        assertThat(registry.counter("agent.idempotency.replays",
                "tool", "create_reminder_ticket").count()).isEqualTo(2.0);
    }

    @Test
    void recordsErrorExecutionCounter() {
        // "错误执行率" — 跟普通 FAILURE 区分，标记为 agent 自身错误（治理层/编排层异常）
        metrics.recordErrorExecution("kb_search", "NPE");
        assertThat(registry.counter("agent.tool.errors",
                "tool", "kb_search", "type", "NPE").count()).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2.3: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentMetricsTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — `AgentMetrics` 类找不到。

- [ ] **Step 2.4: 实现 AgentMetrics**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentMetrics.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 4 大评估指标 — 对齐「路条编程」文章 §"评估指标要变"。
 *
 * <h2>4 个核心指标</h2>
 * <ol>
 *   <li><b>agent.tool.invocations</b> — 端到端调用计数（带 outcome 标签）</li>
 *   <li><b>agent.tool.latency</b> — 端到端耗时分布（Timer，按 tool 切分）</li>
 *   <li><b>agent.handoffs</b> — 转人工次数（按 reason/channel 切分）</li>
 *   <li><b>agent.idempotency.replays</b> — 幂等回放次数（评估"系统稳定性"）</li>
 *   <li><b>agent.tool.errors</b> — 错误执行次数（治理层/编排层异常，跟普通 FAILURE 区分）</li>
 * </ol>
 *
 * <h2>不是端到端解决率 — 那要靠业务反馈</h2>
 * <p>"端到端问题解决率"（文章要求）需要业务系统反馈用户最终是否解决了问题，
 * 这是 out-of-band 信号，不在 Agent Metrics 范围内。本类只覆盖"Agent 自身可观测"。</p>
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 工具调用埋点（每次 invoke 调一次） */
    public void recordToolInvocation(String toolName, AgentOutcome outcome, long latencyMs) {
        registry.counter("agent.tool.invocations",
                "tool", toolName,
                "outcome", outcome.name()).increment();

        Timer timer = Timer.builder("agent.tool.latency")
                .description("Agent tool invocation latency")
                .tag("tool", toolName)
                .register(registry);
        timer.record(Duration.ofMillis(latencyMs));
    }

    /** 转人工埋点（HANDOFF_REQUIRED 时调） */
    public void recordHandoff(String toolName, String reason, String handoffChannel) {
        registry.counter("agent.handoffs",
                "tool", toolName,
                "reason", reason,
                "channel", handoffChannel).increment();
    }

    /** 幂等回放埋点（REPLAY outcome 时调） */
    public void recordIdempotencyReplay(String toolName) {
        registry.counter("agent.idempotency.replays",
                "tool", toolName).increment();
    }

    /** 错误执行埋点（治理层/编排层异常，不是工具业务 FAILURE） */
    public void recordErrorExecution(String toolName, String errorType) {
        registry.counter("agent.tool.errors",
                "tool", toolName,
                "type", errorType).increment();
    }
}
```

- [ ] **Step 2.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentMetricsTest 2>&1 | tail -10
```
Expected: `Tests run: 5, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 2.6: 跑全套确认不破坏**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **51 tests pass** (46 + 5)。

- [ ] **Step 2.7: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/pom.xml rag-agent/src
git commit -m "feat(agent): AgentMetrics — 4 类 Micrometer 指标 (Phase 10 Task 2)

对齐「路条编程」文章 §评估指标要变 — 5 个 Micrometer 计数器/计时器:
- agent.tool.invocations (counter, tags: tool, outcome)
- agent.tool.latency (timer, tag: tool)
- agent.handoffs (counter, tags: tool, reason, channel)
- agent.idempotency.replays (counter, tag: tool)
- agent.tool.errors (counter, tags: tool, type)

注：端到端问题解决率需业务反馈信号，不在本类范围。

micrometer-registry-prometheus 加为 provided (rag-app 已带,
rag-agent 单测用 SimpleMeterRegistry). 51 tests pass."
```

---

## Task 3: DefaultRiskGate 加 L3 金额门控 + L4 admin 校验

**Files:**
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGate.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RiskGate.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java` (加 maxAmountCents 字段)
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/AmountLimitExceededException.java` (NEW)

- [ ] **Step 3.1: 写 AmountLimitExceededException 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/exception/AmountLimitExceededExceptionTest.java`

```java
package io.github.yysf1949.rag.agent.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AmountLimitExceededExceptionTest {

    @Test
    void carriesToolNameAndLimits() {
        AmountLimitExceededException e = new AmountLimitExceededException(
                "create_refund", 50000L, 10000L);
        assertThat(e.getMessage())
                .contains("create_refund")
                .contains("50000")
                .contains("10000");
        assertThat(e.toolName()).isEqualTo("create_refund");
        assertThat(e.requestedCents()).isEqualTo(50000L);
        assertThat(e.limitCents()).isEqualTo(10000L);
    }
}
```

- [ ] **Step 3.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AmountLimitExceededExceptionTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — 类找不到。

- [ ] **Step 3.3: 实现 AmountLimitExceededException**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/exception/AmountLimitExceededException.java`

```java
package io.github.yysf1949.rag.agent.exception;

/**
 * L3 工具金额超限 — 触发转人工（HANDOFF_REQUIRED）。
 *
 * <p>对齐「路条编程」文章 §"评估指标要变" — L3 改业务态的工具要按金额做二次确认。
 * Agent 不能"自动退款 5 万"，超过 {@code maxAmountCents} 必须转人工审批。</p>
 */
public class AmountLimitExceededException extends RuntimeException {

    private final String toolName;
    private final long requestedCents;
    private final long limitCents;

    public AmountLimitExceededException(String toolName, long requestedCents, long limitCents) {
        super(String.format("Tool [%s] amount %d cents exceeds L3 limit %d cents — handoff required",
                toolName, requestedCents, limitCents));
        this.toolName = toolName;
        this.requestedCents = requestedCents;
        this.limitCents = limitCents;
    }

    public String toolName() { return toolName; }
    public long requestedCents() { return requestedCents; }
    public long limitCents() { return limitCents; }
}
```

- [ ] **Step 3.4: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AmountLimitExceededExceptionTest 2>&1 | tail -10
```
Expected: `Tests run: 1, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 3.5: 扩展 ToolDescriptor 加 maxAmountCents 字段**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolDescriptor.java`：

找到 `public record ToolDescriptor(` 这一行，**完整替换**为：

```java
public record ToolDescriptor(
        String name,
        String description,
        RiskLevel riskLevel,
        boolean idempotent,
        boolean requiresIdempotencyKey,
        Long maxAmountCents,
        Object bean,
        Method method
) {
```

**注意**: 在 record 字段后 close parenthesis `) {` 之前插入新字段。

- [ ] **Step 3.6: 扩展 @ToolSpec 注解加 maxAmountCents**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/action/ToolSpec.java`：

找到 `@interface ToolSpec {` 块，**新增一个方法**：

```java
    /**
     * L3 写操作工具的金额上限（分）。超过此金额必须转人工审批。
     * 缺省 -1 表示不限。
     */
    long maxAmountCents() default -1L;
```

- [ ] **Step 3.7: 跑测试确认 ToolDescriptor 改动不破坏**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -10
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **52 tests pass** (51 + 1)。

如果 ToolRegistry 编译失败，**修改 ToolRegistry** 给 `ToolDescriptor` 构造时传 `null` 作为 maxAmountCents 参数（暂未启用金额门控）。

- [ ] **Step 3.8: 写 DefaultRiskGate 扩展测试（先红）**

Modify `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGateTest.java`：

在文件**末尾**追加 3 个测试：

```java
    // ─── Phase 10 新增: L3 金额门控 + L4 admin 校验 ──────────────────────

    @Test
    void l3WithAmountUnderLimitPasses() {
        ToolDescriptor desc = new ToolDescriptor(
                "create_refund", "create refund", RiskLevel.L3_BUSINESS_STATE,
                true, true, 100_00L, // maxAmountCents = 100 元
                new Object(), getMethod());
        IdempotencyKey key = IdempotencyKey.of("refund-1");
        // 50 元（5000 cents）不超限
        gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of()), key, 50_00L);
    }

    @Test
    void l3WithAmountOverLimitThrowsHandoff() {
        ToolDescriptor desc = new ToolDescriptor(
                "create_refund", "create refund", RiskLevel.L3_BUSINESS_STATE,
                true, true, 100_00L, // maxAmountCents = 100 元
                new Object(), getMethod());
        IdempotencyKey key = IdempotencyKey.of("refund-1");
        // 500 元（50000 cents）超限
        assertThatThrownBy(() ->
                gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of()), key, 500_00L))
                .isInstanceOf(AmountLimitExceededException.class)
                .hasMessageContaining("50000")
                .hasMessageContaining("10000");
    }

    @Test
    void l4WithoutAdminRoleThrowsDenied() {
        ToolDescriptor desc = new ToolDescriptor(
                "direct_refund", "direct refund (admin)", RiskLevel.L4_HIGH_RISK,
                true, true, null,
                new Object(), getMethod());
        IdempotencyKey key = IdempotencyKey.of("refund-admin-1");
        // 普通用户被拒
        assertThatThrownBy(() ->
                gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of("user")), key, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("L4_HIGH_RISK");
    }

    @Test
    void l4WithAdminRolePasses() {
        ToolDescriptor desc = new ToolDescriptor(
                "direct_refund", "direct refund (admin)", RiskLevel.L4_HIGH_RISK,
                true, true, null,
                new Object(), getMethod());
        IdempotencyKey key = IdempotencyKey.of("refund-admin-1");
        gate.check(desc, identity("admin-1", "tenant-1", "session-1", List.of("admin")), key, null);
    }
```

注意：
- 需要 `identity(...)` 辅助方法（用 lambda 或 method extraction — 现有测试类可能已定义）
- 需要 `getMethod()` 辅助方法返回任意 Method（现有 `KbSearchTool` 的 search 方法即可，或用 `Object.class.getMethods()[0]`）

- [ ] **Step 3.9: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=DefaultRiskGateTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE` 或部分 FAIL — `check(...)` 旧签名 3 参数，新签名 4 参数。

- [ ] **Step 3.10: 修改 RiskGate interface 加 requestedAmountCents 参数**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RiskGate.java`：

完整替换为：

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.ToolDescriptor;

/**
 * 风险门控接口。
 *
 * <h2>Phase 10 改造</h2>
 * <p>{@code check} 加第 4 参数 {@code requestedAmountCents} — 用于 L3 金额门控。
 * 如果工具没有金额概念（如查询类），传 {@code null}。</p>
 */
public interface RiskGate {
    void check(ToolDescriptor descriptor, AgentIdentity identity,
               IdempotencyKey idempotencyKey, Long requestedAmountCents);
}
```

- [ ] **Step 3.11: 修改 DefaultRiskGate 实现金额门控**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/DefaultRiskGate.java`：

找到 `public void check(ToolDescriptor descriptor, AgentIdentity identity, IdempotencyKey idemKey) {`，完整替换为：

```java
    @Override
    public void check(ToolDescriptor descriptor, AgentIdentity identity,
                      IdempotencyKey idemKey, Long requestedAmountCents) {
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

        // 工具声明 requiresIdempotencyKey 但 token 为空
        if (descriptor.requiresIdempotencyKey() && (idemKey.rawToken() == null || idemKey.rawToken().isBlank())) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] requiresIdempotencyKey but token is blank", descriptor.name()));
        }

        // Phase 10: L3 金额门控 — 超过 maxAmountCents 抛 AmountLimitExceededException
        if (level == RiskLevel.L3_BUSINESS_STATE
                && descriptor.maxAmountCents() != null
                && descriptor.maxAmountCents() >= 0
                && requestedAmountCents != null
                && requestedAmountCents > descriptor.maxAmountCents()) {
            throw new AmountLimitExceededException(
                    descriptor.name(), requestedAmountCents, descriptor.maxAmountCents());
        }
    }
```

注意：需要在文件**顶部 import** 新加的 `AmountLimitExceededException`：

```java
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
```

- [ ] **Step 3.12: 修改 DefaultAgentLoop 适配新 check 签名**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoop.java`：

找到 `riskGate.check(...)` 调用，替换为 4 参数版本：

```java
        // 风险门控（L3 金额门控：L3 工具可传 requestedAmountCents，null 表示无金额概念）
        Long amountCents = extractAmountCents(desc, request);
        riskGate.check(desc, request.identity(), request.idempotencyKey(), amountCents);
```

并在 `DefaultAgentLoop` 类**末尾**追加私有方法：

```java
    /**
     * 从工具入参中提取 amountCents（反射读"amountCents"或"amount"long 型字段）。
     * L1 / 无金额字段的工具返回 null。
     */
    private Long extractAmountCents(ToolDescriptor desc, AgentRequest request) {
        Object payload = request.requestPayload();
        if (payload == null) return null;
        try {
            java.lang.reflect.Field amountCentsField = payload.getClass().getDeclaredField("amountCents");
            amountCentsField.setAccessible(true);
            Object v = amountCentsField.get(payload);
            if (v instanceof Long l) return l;
        } catch (NoSuchFieldException ignored) {
            // try "amount" field next
        } catch (IllegalAccessException e) {
            log.warn("Failed to read amountCents from {}: {}", payload.getClass().getSimpleName(), e.getMessage());
        }
        try {
            java.lang.reflect.Field amountField = payload.getClass().getDeclaredField("amount");
            amountField.setAccessible(true);
            Object v = amountField.get(payload);
            if (v instanceof Long l) return l;
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // no amount field
        }
        return null;
    }
```

- [ ] **Step 3.13: 跑全套测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -10
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **55 tests pass** (52 + 3 新增 — 含 L3 under/over + L4 admin/denied)。

- [ ] **Step 3.14: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src
git commit -m "feat(agent): L3 amount limit + L4 admin role enforcement (Phase 10 Task 3)

L3 业务态工具加金额门控 — maxAmountCents 超过抛 AmountLimitExceededException
(触发后续 Task 8 的 handoff 流程)。L4 高风险工具强制 admin 角色 (现有
RiskGate 框架补全 — Phase 9 只声明了规则，没测试覆盖)。

DefaultAgentLoop.extractAmountCents() 反射读工具入参的 amountCents/amount 字段,
L1 / 无金额字段返回 null。DefaultRiskGate.check() 升级为 4 参数 (descriptor +
identity + idempotencyKey + requestedAmountCents). 55 tests pass."
```

---

## Task 4: OrderTool (L1 查询 + L3 取消) + OrderRepository

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderTool.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/OrderToolTest.java`

- [ ] **Step 4.1: 写 OrderRepository 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/OrderToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderToolTest {

    private OrderRepository repo;
    private OrderTool tool;

    @BeforeEach
    void setUp() {
        repo = new OrderRepository();
        // 预置数据
        repo.save(new OrderRepository.Order(
                "ORD-1", "tenant-1", "user-1", 100_00L, "SHIPPED"));
        tool = new OrderTool(repo);
    }

    @Test
    void getOrderByIdFound() {
        var resp = tool.getOrder(new OrderTool.GetOrderRequest(
                "tenant-1", "user-1", "ORD-1"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.status()).isEqualTo("SHIPPED");
        assertThat(resp.amountCents()).isEqualTo(100_00L);
    }

    @Test
    void getOrderByIdCrossTenantBlocked() {
        assertThatThrownBy(() ->
                tool.getOrder(new OrderTool.GetOrderRequest(
                        "tenant-2", "user-1", "ORD-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant-2");
    }

    @Test
    void cancelOrderHappyPath() {
        var resp = tool.cancelOrder(new OrderTool.CancelOrderRequest(
                "tenant-1", "user-1", "ORD-1", 100_00L, "用户主动取消"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderOnAlreadyCancelledIdempotent() {
        // 第一次取消
        tool.cancelOrder(new OrderTool.CancelOrderRequest(
                "tenant-1", "user-1", "ORD-1", 100_00L, "first"));
        // 第二次幂等
        var resp = tool.cancelOrder(new OrderTool.CancelOrderRequest(
                "tenant-1", "user-1", "ORD-1", 100_00L, "second"));
        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderOnShippedFails() {
        // 已发货订单不能取消
        repo.save(new OrderRepository.Order(
                "ORD-2", "tenant-1", "user-1", 50_00L, "DELIVERED"));
        assertThatThrownBy(() ->
                tool.cancelOrder(new OrderTool.CancelOrderRequest(
                        "tenant-1", "user-1", "ORD-2", 50_00L, "try cancel delivered")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERED");
    }
}
```

- [ ] **Step 4.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=OrderToolTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE`。

- [ ] **Step 4.3: 实现 OrderRepository**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderRepository.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版订单仓储 — 演示用。
 *
 * <h2>为什么不直连数据库</h2>
 * <p>本项目是 RAG 引擎，订单/退款等业务系统是外部依赖。本类用内存版模拟
 * 业务 Service 行为 — 真实生产应该走 port-and-adapter，从 {@code rag-pipeline}
 * 注入真实 OrderService 实现。</p>
 *
 * <h2>租户硬墙</h2>
 * <p>{@code findByIdAndTenant} 强制 tenantId 匹配 — 跨租户查询直接抛
 * IllegalArgumentException，对齐「路条编程」文章 §"Agent 不能绕过原有业务规则"。</p>
 */
@Repository
public class OrderRepository {

    private final Map<String, Order> store = new ConcurrentHashMap<>();

    public Order save(Order order) {
        store.put(order.orderId(), order);
        return order;
    }

    public Optional<Order> findByIdAndTenant(String orderId, String tenantId) {
        Order o = store.get(orderId);
        if (o == null) return Optional.empty();
        if (!o.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Cross-tenant access denied: requested by tenant=" + tenantId
                            + " but order belongs to tenant=" + o.tenantId());
        }
        return Optional.of(o);
    }

    public record Order(
            String orderId,
            String tenantId,
            String userId,
            long amountCents,
            String status  // CREATED / PAID / SHIPPED / DELIVERED / CANCELLED
    ) { }
}
```

- [ ] **Step 4.4: 实现 OrderTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 订单工具 — L1 查询 + L3 取消。
 *
 * <h2>4 级风险对照</h2>
 * <ul>
 *   <li>{@code get_order} — L1_READ（只读，参数含 amountCents 仅作展示）</li>
 *   <li>{@code cancel_order} — L3_BUSINESS_STATE（写业务态，单笔 ≤ 100 元不需转人工）</li>
 * </ul>
 *
 * <h2>maxAmountCents 含义</h2>
 * <p>对 cancel_order：单笔订单金额上限 100 元 = 10000 cents。超过此金额必须转人工审批。</p>
 */
@Component
public class OrderTool {

    /** 取消订单单笔金额上限（分）— 100 元 */
    public static final long CANCEL_MAX_AMOUNT_CENTS = 100_00L;

    private final OrderRepository repo;

    public OrderTool(OrderRepository repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "get_order",
            description = "查询订单详情（订单号、金额、状态）。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public GetOrderResponse getOrder(GetOrderRequest req) {
        var order = repo.findByIdAndTenant(req.orderId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + req.orderId()));
        return new GetOrderResponse(
                order.orderId(), order.status(), order.amountCents(), order.userId());
    }

    @ToolSpec(
            name = "cancel_order",
            description = "取消订单（未发货可取消，超过 100 元需转人工审批）。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 100_00L  // 100 元上限
    )
    public CancelOrderResponse cancelOrder(CancelOrderRequest req) {
        var order = repo.findByIdAndTenant(req.orderId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + req.orderId()));
        // 已发货/已送达/已取消都不能再取消
        if (!Set.of("CREATED", "PAID").contains(order.status())) {
            throw new IllegalStateException("Cannot cancel order in status: " + order.status());
        }
        var cancelled = new OrderRepository.Order(
                order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), "CANCELLED");
        repo.save(cancelled);
        return new CancelOrderResponse(order.orderId(), "CANCELLED", req.reason());
    }

    public record GetOrderRequest(String tenantId, String userId, String orderId) { }
    public record GetOrderResponse(String orderId, String status, long amountCents, String userId) { }
    public record CancelOrderRequest(String tenantId, String userId, String orderId,
                                     long amountCents, String reason) { }
    public record CancelOrderResponse(String orderId, String status, String reason) { }
}
```

- [ ] **Step 4.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=OrderToolTest 2>&1 | tail -10
```
Expected: `Tests run: 5, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 4.6: 跑全套确认 ToolRegistry 扫描能识别 OrderTool**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **60 tests pass** (55 + 5)。

如果 ToolRegistryTest 失败（OrderTool 多 2 个工具，registry 计数变），调整 `ToolRegistryTest` 的 assertEquals 数字（4 → 6 或更新测试为">=4"）。

- [ ] **Step 4.7: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src
git commit -m "feat(agent): OrderTool — L1 get_order + L3 cancel_order (Phase 10 Task 4)

P0.1 业务工具 — 订单查询 (L1) + 订单取消 (L3, maxAmountCents=10000).
跨租户访问直接抛 IllegalArgumentException — 对齐「路条编程」文章
§\"Agent 不能绕过原有业务规则\". 60 tests pass."
```

---

## Task 5: RefundTool (L3 创建申请 + L4 直接退款) + RefundRepository

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundTool.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/RefundToolTest.java`

- [ ] **Step 5.1: 写 RefundToolTest 失败测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/RefundToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundToolTest {

    private RefundRepository repo;
    private RefundTool tool;

    @BeforeEach
    void setUp() {
        repo = new RefundRepository();
        tool = new RefundTool(repo);
    }

    @Test
    void createRefundApplication() {
        var resp = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "商品质量问题"));
        assertThat(resp.refundId()).startsWith("REF-");
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(resp.amountCents()).isEqualTo(50_00L);
    }

    @Test
    void createRefundOverLimitGoesToHandoff() {
        // 1000 元超过 500 元上限
        assertThatThrownBy(() ->
                tool.createRefund(new RefundTool.CreateRefundRequest(
                        "tenant-1", "user-1", "ORD-1", 1000_00L, "高额退款")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    @Test
    void approveRefundTransitionsToApproved() {
        var created = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok"));
        // L4 直接退款（admin role 由 RiskGate 校验，本测试只测业务逻辑）
        var resp = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        assertThat(resp.status()).isEqualTo("APPROVED");
    }

    @Test
    void approveRefundTwiceIdempotent() {
        var created = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok"));
        var first = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        var second = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(second.status()).isEqualTo("APPROVED");
        // 不应该有重复扣款
        assertThat(repo.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 5.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RefundToolTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE`。

- [ ] **Step 5.3: 实现 RefundRepository**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundRepository.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版退款仓储。
 */
@Repository
public class RefundRepository {

    private final Map<String, Refund> store = new ConcurrentHashMap<>();

    public Refund save(Refund refund) {
        store.put(refund.refundId(), refund);
        return refund;
    }

    public Optional<Refund> findByIdAndTenant(String refundId, String tenantId) {
        Refund r = store.get(refundId);
        if (r == null) return Optional.empty();
        if (!r.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Cross-tenant refund access");
        }
        return Optional.of(r);
    }

    public int count() { return store.size(); }

    public record Refund(
            String refundId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reason,
            String status  // PENDING / APPROVED / REJECTED
    ) { }

    public static String newRefundId() { return "REF-" + UUID.randomUUID().toString().substring(0, 8); }
}
```

- [ ] **Step 5.4: 实现 RefundTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

/**
 * 退款工具 — L3 创建申请 + L4 直接退款。
 *
 * <h2>4 级风险对照</h2>
 * <ul>
 *   <li>{@code create_refund} — L3_BUSINESS_STATE（写业务态，单笔 ≤ 500 元不需转人工）</li>
 *   <li>{@code approve_refund} — L4_HIGH_RISK（直接打款，admin 角色强制）</li>
 * </ul>
 */
@Component
public class RefundTool {

    /** 创建退款单笔金额上限（分）— 500 元 */
    public static final long CREATE_MAX_AMOUNT_CENTS = 500_00L;

    private final RefundRepository repo;

    public RefundTool(RefundRepository repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "create_refund",
            description = "创建退款申请（待审批），单笔超过 500 元需转人工。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 500_00L  // 500 元上限
    )
    public CreateRefundResponse createRefund(CreateRefundRequest req) {
        // 金额门控 — RiskGate 也会检查，这里写一次保证直接调用时也走门控
        if (req.amountCents() > CREATE_MAX_AMOUNT_CENTS) {
            throw new io.github.yysf1949.rag.agent.exception.AmountLimitExceededException(
                    "create_refund", req.amountCents(), CREATE_MAX_AMOUNT_CENTS);
        }
        var refund = new RefundRepository.Refund(
                RefundRepository.newRefundId(),
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reason(), "PENDING");
        repo.save(refund);
        return new CreateRefundResponse(refund.refundId(), "PENDING", refund.amountCents());
    }

    @ToolSpec(
            name = "approve_refund",
            description = "审批通过退款申请（直接打款 — L4 高风险，仅 admin 角色可执行）。",
            riskLevel = RiskLevel.L4_HIGH_RISK,
            idempotent = true,  // 同 refundId + admin 重复审批 = 幂等
            requiresIdempotencyKey = true
    )
    public ApproveRefundResponse approveRefund(ApproveRefundRequest req) {
        var existing = repo.findByIdAndTenant(req.refundId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Refund not found"));
        // 幂等：已 APPROVED 直接返回
        if ("APPROVED".equals(existing.status())) {
            return new ApproveRefundResponse(existing.refundId(), "APPROVED", existing.amountCents());
        }
        var approved = new RefundRepository.Refund(
                existing.refundId(), existing.tenantId(), existing.userId(),
                existing.orderId(), existing.amountCents(), existing.reason(), "APPROVED");
        repo.save(approved);
        return new ApproveRefundResponse(approved.refundId(), "APPROVED", approved.amountCents());
    }

    public record CreateRefundRequest(String tenantId, String userId, String orderId,
                                      long amountCents, String reason) { }
    public record CreateRefundResponse(String refundId, String status, long amountCents) { }
    public record ApproveRefundRequest(String tenantId, String adminUserId, String refundId,
                                       long amountCents) { }
    public record ApproveRefundResponse(String refundId, String status, long amountCents) { }
}
```

- [ ] **Step 5.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RefundToolTest 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5.6: 跑全套**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **64 tests pass**。

- [ ] **Step 5.7: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src
git commit -m "feat(agent): RefundTool — L3 create_refund + L4 approve_refund (Phase 10 Task 5)

P0.2 业务工具 — 退款申请 (L3, maxAmountCents=50000) + 退款审批 (L4, admin
角色强制). L4 approve 重复审批幂等 (已 APPROVED 直接返回, 不会重复扣款).
64 tests pass."
```

---

## Task 6: CouponTool (L3 补发) + CouponRepository

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponTool.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/CouponToolTest.java`

- [ ] **Step 6.1: 写 CouponToolTest（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/CouponToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponToolTest {

    private CouponRepository repo;
    private CouponTool tool;

    @BeforeEach
    void setUp() {
        repo = new CouponRepository();
        tool = new CouponTool(repo);
    }

    @Test
    void issueCouponHappyPath() {
        var resp = tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-1", 20_00L, "WELCOME_BACK"));
        assertThat(resp.couponId()).startsWith("CPN-");
        assertThat(resp.amountCents()).isEqualTo(20_00L);
    }

    @Test
    void issueCouponOverLimitThrowsHandoff() {
        // 1000 元超过 200 元上限
        assertThatThrownBy(() ->
                tool.issueCoupon(new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-1", "ORD-1", 1000_00L, "BIG_REWARD")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    @Test
    void listActiveCoupons() {
        tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-1", 20_00L, "W1"));
        tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-2", 30_00L, "W2"));
        var resp = tool.listActiveCoupons(new CouponTool.ListCouponsRequest(
                "tenant-1", "user-1"));
        assertThat(resp.coupons()).hasSize(2);
    }
}
```

- [ ] **Step 6.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=CouponToolTest 2>&1 | tail -10
```

- [ ] **Step 6.3: 实现 CouponRepository**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponRepository.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版优惠券仓储。
 */
@Repository
public class CouponRepository {

    private final Map<String, Coupon> store = new ConcurrentHashMap<>();

    public Coupon save(Coupon coupon) {
        store.put(coupon.couponId(), coupon);
        return coupon;
    }

    public List<Coupon> findActiveByTenantAndUser(String tenantId, String userId) {
        return store.values().stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .filter(c -> c.userId().equals(userId))
                .filter(c -> "ACTIVE".equals(c.status()))
                .collect(Collectors.toList());
    }

    public record Coupon(
            String couponId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reasonTag,
            String status  // ACTIVE / USED / EXPIRED
    ) { }

    public static String newCouponId() { return "CPN-" + UUID.randomUUID().toString().substring(0, 8); }
}
```

- [ ] **Step 6.4: 实现 CouponTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券工具 — L3 补发 + L1 查询。
 */
@Component
public class CouponTool {

    /** 单张优惠券金额上限（分）— 200 元 */
    public static final long ISSUE_MAX_AMOUNT_CENTS = 200_00L;

    private final CouponRepository repo;

    public CouponTool(CouponRepository repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "issue_coupon",
            description = "补发优惠券（单张超过 200 元需转人工审批）。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 200_00L  // 200 元上限
    )
    public IssueCouponResponse issueCoupon(IssueCouponRequest req) {
        if (req.amountCents() > ISSUE_MAX_AMOUNT_CENTS) {
            throw new io.github.yysf1949.rag.agent.exception.AmountLimitExceededException(
                    "issue_coupon", req.amountCents(), ISSUE_MAX_AMOUNT_CENTS);
        }
        var coupon = new CouponRepository.Coupon(
                CouponRepository.newCouponId(),
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reasonTag(), "ACTIVE");
        repo.save(coupon);
        return new IssueCouponResponse(coupon.couponId(), coupon.amountCents(), coupon.status());
    }

    @ToolSpec(
            name = "list_active_coupons",
            description = "查询用户当前有效优惠券列表（只读）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public ListCouponsResponse listActiveCoupons(ListCouponsRequest req) {
        var coupons = repo.findActiveByTenantAndUser(req.tenantId(), req.userId());
        var list = coupons.stream()
                .map(c -> new CouponView(c.couponId(), c.amountCents(), c.reasonTag()))
                .toList();
        return new ListCouponsResponse(list);
    }

    public record IssueCouponRequest(String tenantId, String userId, String orderId,
                                     long amountCents, String reasonTag) { }
    public record IssueCouponResponse(String couponId, long amountCents, String status) { }
    public record ListCouponsRequest(String tenantId, String userId) { }
    public record ListCouponsResponse(List<CouponView> coupons) { }
    public record CouponView(String couponId, long amountCents, String reasonTag) { }
}
```

- [ ] **Step 6.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=CouponToolTest 2>&1 | tail -10
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 6.6: 跑全套 + Commit**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/src
git commit -m "feat(agent): CouponTool — L3 issue_coupon + L1 list_active_coupons (Phase 10 Task 6)

P0.1 业务工具 — 优惠券补发 (L3, maxAmountCents=20000) + 列表查询 (L1).
67 tests pass."
```

---

## Task 7: LogisticsTool (L1 物流查询)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/LogisticsTool.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/LogisticsToolTest.java`

- [ ] **Step 7.1: 写 LogisticsToolTest（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/builtin/LogisticsToolTest.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticsToolTest {

    private final LogisticsTool tool = new LogisticsTool();

    @Test
    void queryLogisticsHappyPath() {
        var resp = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-1"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.currentLocation()).isNotBlank();
        assertThat(resp.events()).isNotEmpty();
    }

    @Test
    void queryLogisticsUnknownOrder() {
        // mock 实现对未知订单返回空事件列表
        var resp = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-UNKNOWN"));
        assertThat(resp.events()).isEmpty();
        assertThat(resp.currentLocation()).isEqualTo("UNKNOWN");
    }

    @Test
    void createReminderLogisticsUsesOrderId() {
        // 演示: query + create_reminder_ticket 串联 (实际由编排层组合)
        var query = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-DELAYED"));
        assertThat(query.events()).extracting(LogisticsTool.LogisticsEvent::status)
                .contains("DELAYED");
    }
}
```

- [ ] **Step 7.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=LogisticsToolTest 2>&1 | tail -10
```

- [ ] **Step 7.3: 实现 LogisticsTool**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/LogisticsTool.java`

```java
package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 物流工具 — L1 只读查询。
 *
 * <h2>对齐文章 §"查询 ≠ 执行"</h2>
 * <p>物流查询是 Agent 客服最常见的开场工具：先查物流，再决定要不要催发货
 * （用 {@code create_reminder_ticket}）— 这是"低风险只读 → 高风险写"的标准分流。</p>
 */
@Component
public class LogisticsTool {

    @ToolSpec(
            name = "query_logistics",
            description = "查询订单物流轨迹（只读）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public QueryResponse queryLogistics(QueryRequest req) {
        // mock 实现：返回固定的演示轨迹
        if (req.orderId().contains("DELAYED")) {
            return new QueryResponse(
                    req.orderId(),
                    "中转仓",
                    List.of(
                            new LogisticsEvent("2026-06-15T10:00:00Z", "杭州中转仓", "IN_TRANSIT"),
                            new LogisticsEvent("2026-06-16T08:00:00Z", "上海中转仓", "DELAYED")),
                    Instant.now().toString());
        }
        if (req.orderId().contains("UNKNOWN")) {
            return new QueryResponse(req.orderId(), "UNKNOWN", List.of(), null);
        }
        return new QueryResponse(
                req.orderId(),
                "北京-客户地址",
                List.of(
                        new LogisticsEvent("2026-06-17T14:00:00Z", "杭州发货", "SHIPPED"),
                        new LogisticsEvent("2026-06-17T20:00:00Z", "北京中转", "IN_TRANSIT"),
                        new LogisticsEvent("2026-06-18T08:00:00Z", "派送中", "DELIVERING")),
                "2026-06-18T15:00:00Z");
    }

    public record QueryRequest(String tenantId, String userId, String orderId) { }
    public record QueryResponse(String orderId, String currentLocation,
                                List<LogisticsEvent> events, String estimatedArrival) { }
    public record LogisticsEvent(String timestamp, String location, String status) { }
}
```

- [ ] **Step 7.4: 跑测试 + 全套 + Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=LogisticsToolTest 2>&1 | tail -5
mvn -pl rag-agent test -q 2>&1 | tail -3
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/src
git commit -m "feat(agent): LogisticsTool — L1 query_logistics (Phase 10 Task 7)

P0.1 业务工具 — 物流轨迹查询 (L1, 只读). 演示 Agent 客服标准开场:
\"先查物流 → 决定要不要催发货 (L2 create_reminder_ticket)\".
70 tests pass."
```

---

## Task 8: HandoffService + HandoffContext + 异常 + 队列

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffReason.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffChannel.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffContext.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffRequiredException.java` (relocate from exception/ to handoff/)
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HumanReviewQueue.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffService.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/handoff/HandoffServiceTest.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/handoff/HumanReviewQueueTest.java`

注: 把 Task 1 在 `exception/` 包的 HandoffRequiredException 移到 `handoff/` 包（更内聚），并在 AgentLoop 引用的地方改 import。

- [ ] **Step 8.1: 实现 HandoffReason enum + HandoffChannel enum**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffReason.java`

```java
package io.github.yysf1949.rag.agent.handoff;

/**
 * 转人工原因 — 对齐「路条编程」文章 §"人工确认不是失败"。
 *
 * <p>每种原因对应不同的"Agent 已完成的前置工作" — 人工客服接手后
 * 不用重新问用户。</p>
 */
public enum HandoffReason {
    /** L3 金额超限 — 工具已查订单/规则/已生成草稿 */
    AMOUNT_LIMIT_EXCEEDED,
    /** L4 工具但用户非 admin — 工具已查所有信息，需要 admin 审批 */
    INSUFFICIENT_PRIVILEGE,
    /** 模型多次重试失败 — 工具尝试过所有相关查询 */
    RETRY_EXHAUSTED,
    /** 业务规则命中"必须人工"分支 — 工具已做规则匹配 */
    BUSINESS_RULE_MANDATES_HUMAN,
    /** 用户主动要求人工 */
    USER_REQUESTED
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffChannel.java`

```java
package io.github.yysf1949.rag.agent.handoff;

/**
 * 转接渠道 — Agent 把"待处理项"丢到哪个渠道等人工。
 */
public enum HandoffChannel {
    /** 工单系统（异步，最常见） */
    WORK_ORDER,
    /** 在线客服（实时，对应 Agent 客服场景） */
    LIVE_CHAT,
    /** 电话外呼（紧急情况） */
    PHONE
}
```

- [ ] **Step 8.2: 实现 HandoffContext record**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffContext.java`

```java
package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;

import java.util.List;

/**
 * 转人工上下文 — Agent 把已经做完的工作打包给人工客服。
 *
 * <h2>对齐「路条编程」文章 §"人工确认不是失败"</h2>
 * <p>理想 Agent 转人工前应该完成：</p>
 * <ol>
 *   <li>用户身份确认 — {@link AgentIdentity} 携带</li>
 *   <li>订单/工单信息查询 — {@code toolChain} 记录调过的工具</li>
 *   <li>问题分类 — {@code category}</li>
 *   <li>规则匹配 — {@code matchedRules}</li>
 *   <li>风险说明 — {@code riskNote}</li>
 * </ol>
 *
 * <p>人工客服接手后不需要重新问用户"你刚才发生了什么"。</p>
 */
public record HandoffContext(
        AgentIdentity identity,
        String toolName,
        HandoffReason reason,
        HandoffChannel channel,
        String summary,
        String category,
        List<String> matchedRules,
        String riskNote,
        List<String> toolChain,
        String toolChainJson
) {
    public static HandoffContext forAmountLimit(
            AgentIdentity identity, String toolName,
            long amountCents, long limitCents, List<String> toolChain) {
        return new HandoffContext(
                identity, toolName,
                HandoffReason.AMOUNT_LIMIT_EXCEEDED,
                HandoffChannel.WORK_ORDER,
                String.format("User requested %s with amount %d cents (limit %d). Manual review required.",
                        toolName, amountCents, limitCents),
                "HIGH_VALUE_TRANSACTION",
                List.of("amount_exceeds_l3_limit"),
                String.format("Amount %d cents is %d cents over the L3 limit. Fraud risk: low (verified user).",
                        amountCents, amountCents - limitCents),
                toolChain,
                String.join(" -> ", toolChain));
    }

    public static HandoffContext forInsufficientPrivilege(
            AgentIdentity identity, String toolName, List<String> toolChain) {
        return new HandoffContext(
                identity, toolName,
                HandoffReason.INSUFFICIENT_PRIVILEGE,
                HandoffChannel.LIVE_CHAT,
                String.format("User (roles=%s) attempted L4 tool [%s]. Admin approval required.",
                        identity.roles(), toolName),
                "PRIVILEGE_ESCALATION",
                List.of("l4_requires_admin_role"),
                "User is not in admin role. L4 tool execution requires manual approval.",
                toolChain,
                String.join(" -> ", toolChain));
    }
}
```

- [ ] **Step 8.3: 写 HandoffServiceTest（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/handoff/HandoffServiceTest.java`

```java
package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffServiceTest {

    private HumanReviewQueue queue;
    private HandoffService service;

    @BeforeEach
    void setUp() {
        queue = new HumanReviewQueue();
        service = new HandoffService(queue);
    }

    @Test
    void handoffForAmountLimitEnqueues() {
        AgentIdentity id = AgentIdentity.of("tenant-1", "user-1", "session-1", List.of("user"));
        var ctx = HandoffContext.forAmountLimit(id, "create_refund",
                1000_00L, 500_00L, List.of("kb_search", "get_order"));
        service.handoff(ctx);

        var pending = queue.listPending("tenant-1");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolName()).isEqualTo("create_refund");
        assertThat(pending.get(0).reason()).isEqualTo(HandoffReason.AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void handoffForInsufficientPrivilegeEnqueues() {
        AgentIdentity id = AgentIdentity.of("tenant-1", "user-1", "session-1", List.of("user"));
        var ctx = HandoffContext.forInsufficientPrivilege(id, "direct_refund",
                List.of("kb_search"));
        service.handoff(ctx);

        var pending = queue.listPending("tenant-1");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).reason()).isEqualTo(HandoffReason.INSUFFICIENT_PRIVILEGE);
        assertThat(pending.get(0).channel()).isEqualTo(HandoffChannel.LIVE_CHAT);
    }

    @Test
    void completeHandoffRemovesFromQueue() {
        AgentIdentity id = AgentIdentity.of("tenant-1", "user-1", "session-1", List.of("user"));
        var ctx = HandoffContext.forAmountLimit(id, "create_refund",
                1000_00L, 500_00L, List.of());
        service.handoff(ctx);
        var pendingBefore = queue.listPending("tenant-1");
        assertThat(pendingBefore).hasSize(1);

        service.complete(pendingBefore.get(0).handoffId(), "APPROVED",
                "admin-1", "手动审批通过");

        var pendingAfter = queue.listPending("tenant-1");
        assertThat(pendingAfter).isEmpty();
    }
}
```

- [ ] **Step 8.4: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=HandoffServiceTest 2>&1 | tail -10
```

- [ ] **Step 8.5: 实现 HumanReviewQueue + HandoffService**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HumanReviewQueue.java`

```java
package io.github.yysf1949.rag.agent.handoff;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版待人工处理队列。
 *
 * <h2>生产替代</h2>
 * <p>真实生产应该走工单系统（Jira/自研）或客服系统（Zendesk）。本类内存版
 * 满足 demo + 集成测试用。Phase 11 可以加 {@code WorkOrderQueueAdapter} 实现。</p>
 */
@Component
public class HumanReviewQueue {

    private final Map<String, QueueItem> store = new ConcurrentHashMap<>();

    public QueueItem enqueue(QueueItem item) {
        store.put(item.handoffId(), item);
        return item;
    }

    public Optional<QueueItem> complete(String handoffId, String resolution,
                                        String resolvedBy, String note) {
        QueueItem item = store.remove(handoffId);
        if (item == null) return Optional.empty();
        return Optional.of(new QueueItem(
                item.handoffId(), item.context(), item.tenantId(),
                resolution, resolvedBy, note, item.enqueuedAt(), Instant.now().toString()));
    }

    public List<QueueItem> listPending(String tenantId) {
        return store.values().stream()
                .filter(i -> i.tenantId().equals(tenantId))
                .filter(i -> i.resolution() == null)
                .collect(Collectors.toList());
    }

    public record QueueItem(
            String handoffId,
            HandoffContext context,
            String tenantId,
            String resolution,    // null 表示 pending
            String resolvedBy,    // null 表示 pending
            String resolutionNote,
            String enqueuedAt,
            String resolvedAt
    ) { }
}
```

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/handoff/HandoffService.java`

```java
package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 转人工服务 — 编排层在以下情况调用 {@link #handoff(HandoffContext)}：
 * <ul>
 *   <li>RiskGate 抛 {@link io.github.yysf1949.rag.agent.exception.AmountLimitExceededException}</li>
 *   <li>RiskGate 抛 L4 admin role 拒绝</li>
 *   <li>编排层检测到需要人工的特殊分支</li>
 * </ul>
 */
@Service
public class HandoffService {

    private final HumanReviewQueue queue;
    private final AgentMetrics metrics;

    public HandoffService(HumanReviewQueue queue, AgentMetrics metrics) {
        this.queue = queue;
        this.metrics = metrics;
    }

    public HumanReviewQueue.QueueItem handoff(HandoffContext ctx) {
        var item = new HumanReviewQueue.QueueItem(
                newHandoffId(),
                ctx,
                ctx.identity().tenantId(),
                null, null, null,
                Instant.now().toString(),
                null);
        queue.enqueue(item);
        metrics.recordHandoff(ctx.toolName(), ctx.reason().name(), ctx.channel().name());
        return item;
    }

    public HumanReviewQueue.QueueItem complete(String handoffId, String resolution,
                                               String resolvedBy, String note) {
        return queue.complete(handoffId, resolution, resolvedBy, note)
                .orElseThrow(() -> new IllegalArgumentException("Handoff not found: " + handoffId));
    }

    private static String newHandoffId() {
        return "HO-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

- [ ] **Step 8.6: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=HandoffServiceTest 2>&1 | tail -10
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 8.7: HumanReviewQueueTest（独立测试）**

文件: `rag-agent/src/test/java/io/github/ybutterfly443/rag/agent/handoff/HumanReviewQueueTest.java`

(路径修正：`/home/butterfly443/...`)

```java
package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HumanReviewQueueTest {

    @Test
    void listPendingFiltersByTenantAndResolution() {
        var queue = new HumanReviewQueue();
        AgentIdentity id1 = AgentIdentity.of("tenant-1", "u1", "s1", List.of());
        AgentIdentity id2 = AgentIdentity.of("tenant-2", "u2", "s2", List.of());
        var ctx1 = HandoffContext.forAmountLimit(id1, "create_refund", 1000L, 500L, List.of());
        var ctx2 = HandoffContext.forAmountLimit(id2, "create_refund", 1000L, 500L, List.of());

        var i1 = queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-1", ctx1, "tenant-1", null, null, null, "2026-06-18T10:00:00Z", null));
        queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-2", ctx2, "tenant-2", null, null, null, "2026-06-18T10:00:00Z", null));

        var pending1 = queue.listPending("tenant-1");
        var pending2 = queue.listPending("tenant-2");
        assertThat(pending1).hasSize(1);
        assertThat(pending1.get(0).handoffId()).isEqualTo("HO-1");
        assertThat(pending2).hasSize(1);
        assertThat(pending2.get(0).handoffId()).isEqualTo("HO-2");
    }

    @Test
    void completeRemovesItem() {
        var queue = new HumanReviewQueue();
        var ctx = HandoffContext.forAmountLimit(
                AgentIdentity.of("t1", "u1", "s1", List.of()),
                "create_refund", 1000L, 500L, List.of());
        queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-1", ctx, "t1", null, null, null, "now", null));

        var completed = queue.complete("HO-1", "APPROVED", "admin", "ok");
        assertThat(completed).isPresent();
        assertThat(completed.get().resolution()).isEqualTo("APPROVED");

        assertThat(queue.listPending("t1")).isEmpty();
    }
}
```

- [ ] **Step 8.8: 跑 HandoffService + Queue 测试 + 全套**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest='Handoff*Test,HumanReview*Test' 2>&1 | tail -10
mvn -pl rag-agent test -q 2>&1 | tail -3
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: **75 tests pass** (70 + 5)。

- [ ] **Step 8.9: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src
git commit -m "feat(agent): HandoffService + HumanReviewQueue + HandoffContext (Phase 10 Task 8)

P0.3 人工转接机制 — 对齐「路条编程」文章 §\"人工确认不是失败\":
- HandoffReason (5 种触发原因) + HandoffChannel (3 种转接渠道)
- HandoffContext 打包 Agent 已完成的 5 项前置工作
- HumanReviewQueue (内存版, 演示用, 生产接工单系统)
- HandoffService.handoff() 入队 + 触发 metrics 埋点
- 75 tests pass."
```

---

## Task 9: DefaultAgentLoop 接入 metrics + handoff 分流

**Files:**
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoop.java`
- Modify: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoopTest.java`

- [ ] **Step 9.1: 写 DefaultAgentLoop 升级测试（先红）**

Modify `rag-agent/src/test/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoopTest.java`：

在文件末尾追加 3 个测试：

```java
    // ─── Phase 10: handoff 分流 + metrics 埋点 ────────────────────────

    @Test
    void amountLimitExceededTriggersHandoff() {
        // 注册一个 L3 工具 — 100 元上限，调用 500 元触发 AmountLimitExceeded
        var desc = registerTool("huge_refund", RiskLevel.L3_BUSINESS_STATE,
                100_00L, /*requiresIdempotencyKey*/ true);
        var idem = IdempotencyKey.of("huge-1");
        var req = new AgentRequest(identity("user-1", "t1", "s1"),
                "huge_refund", new HugePayload(500_00L), idem,
                AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void l4WithoutAdminTriggersHandoff() {
        var desc = registerTool("admin_tool", RiskLevel.L4_HIGH_RISK,
                null, /*requiresIdempotencyKey*/ true);
        var idem = IdempotencyKey.of("admin-1");
        // user role 触发 L4 admin 拒绝
        var req = new AgentRequest(identity("user-1", "t1", "s1", List.of("user")),
                "admin_tool", new SimplePayload(), idem,
                AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext().reason()).isEqualTo("INSUFFICIENT_PRIVILEGE");
    }

    @Test
    void recordsInvocationMetric() {
        // 验证 AgentMetrics 收到调用埋点
        var desc = registerTool("simple_tool", RiskLevel.L1_READ,
                null, /*requiresIdempotencyKey*/ false);
        var req = new AgentRequest(identity("user-1", "t1", "s1"),
                "simple_tool", new SimplePayload(), null,
                AgentChannel.HTTP, false);

        loop.execute(req);

        // 用 metrics registry 验证埋点
        double invocations = meterRegistry.counter("agent.tool.invocations",
                "tool", "simple_tool", "outcome", "SUCCESS").count();
        assertThat(invocations).isEqualTo(1.0);
    }
```

需要的辅助类型：
- `HugePayload(long amountCents)` 放在同 test 文件
- `SimplePayload()` 放在同 test 文件
- `registerTool(name, level, maxAmount, requiresKey)` 返回 ToolDescriptor 并塞 registry
- `meterRegistry` 是 SimpleMeterRegistry（需要 setUp 里初始化）

- [ ] **Step 9.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=DefaultAgentLoopTest 2>&1 | tail -20
```
Expected: 部分编译失败或 3 个新测试 FAIL（因为 `AgentOutcome` 还没被 DefaultAgentLoop 用，`HandoffContext` 也没接入）。

- [ ] **Step 9.3: 升级 DefaultAgentLoop 接入 handoff + metrics**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/orchestration/DefaultAgentLoop.java`：

完整替换（保持原 `execute` 方法签名，body 升级）：

```java
package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultAgentLoop implements AgentLoop, io.github.yysf1949.rag.agent.api.AgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);

    private final ToolRegistry registry;
    private final RiskGate riskGate;
    private final IdempotencyStore idemStore;
    private final ToolAuditBridge auditBridge;
    private final AgentMetrics metrics;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;

    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idemStore, ToolAuditBridge auditBridge,
                            AgentMetrics metrics, HandoffService handoffService,
                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.riskGate = riskGate;
        this.idemStore = idemStore;
        this.auditBridge = auditBridge;
        this.metrics = metrics;
        this.handoffService = handoffService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();
        AgentOutcome outcome = AgentOutcome.FAILURE;
        Object result = null;
        String errorType = null;
        AgentResponse.HandoffContextPayload handoffPayload = null;

        try {
            ToolDescriptor desc = registry.findByName(request.toolName())
                    .orElseThrow(() -> new ToolNotFoundException(request.toolName()));

            // Phase 10: 金额门控 — L3 工具可能抛 AmountLimitExceededException → 触发 handoff
            Long amountCents = extractAmountCents(desc, request);
            try {
                riskGate.check(desc, request.identity(), request.idempotencyKey(), amountCents);
            } catch (AmountLimitExceededException e) {
                // 走 handoff 流程
                List<String> toolChain = List.of(request.toolName());
                HandoffContext hctx = HandoffContext.forAmountLimit(
                        request.identity(), request.toolName(),
                        e.requestedCents(), e.limitCents(), toolChain);
                var item = handoffService.handoff(hctx);
                handoffPayload = new AgentResponse.HandoffContextPayload(
                        hctx.reason().name(), hctx.channel().name(),
                        hctx.summary(), hctx.toolChainJson());
                outcome = AgentOutcome.HANDOFF_REQUIRED;
                long latency = System.currentTimeMillis() - start;
                metrics.recordToolInvocation(request.toolName(), outcome, latency);
                return new AgentResponse(request.toolName(), outcome, null,
                        "已转人工处理: " + hctx.summary(), latency, handoffPayload);
            } catch (ToolRiskDeniedException e) {
                // L4 admin 拒绝 — 也走 handoff
                if (desc.riskLevel() == RiskLevel.L4_HIGH_RISK) {
                    List<String> toolChain = List.of(request.toolName());
                    HandoffContext hctx = HandoffContext.forInsufficientPrivilege(
                            request.identity(), request.toolName(), toolChain);
                    handoffService.handoff(hctx);
                    handoffPayload = new AgentResponse.HandoffContextPayload(
                            hctx.reason().name(), hctx.channel().name(),
                            hctx.summary(), hctx.toolChainJson());
                    outcome = AgentOutcome.HANDOFF_REQUIRED;
                    long latency = System.currentTimeMillis() - start;
                    metrics.recordToolInvocation(request.toolName(), outcome, latency);
                    return new AgentResponse(request.toolName(), outcome, null,
                            "已转人工处理: 需要 admin 审批", latency, handoffPayload);
                }
                // 普通 DENIED (L2 缺幂等键等) — 记录审计 + 返回 DENIED
                outcome = AgentOutcome.DENIED;
                long latency = System.currentTimeMillis() - start;
                recordAudit(request, desc, "{}", "DENIED", latency);
                metrics.recordToolInvocation(request.toolName(), outcome, latency);
                return new AgentResponse(request.toolName(), outcome, null,
                        e.getMessage(), latency, null);
            }

            // 幂等检查
            if (request.idempotencyKey() != null) {
                String tokenJson = objectMapper.writeValueAsString(request.requestPayload());
                var putResult = idemStore.putIfAbsent(request.idempotencyKey(), null);
                if (putResult.isReplay()) {
                    outcome = AgentOutcome.REPLAY;
                    metrics.recordIdempotencyReplay(request.toolName());
                    long latency = System.currentTimeMillis() - start;
                    metrics.recordToolInvocation(request.toolName(), outcome, latency);
                    return new AgentResponse(request.toolName(), outcome, putResult.value(),
                            "(replay) " + safeToJson(putResult.value()), latency, null);
                }
            }

            // 反射执行
            result = invokeWithInjection(desc, request);
            String responseJson = safeToJson(result);

            // 写回幂等结果
            if (request.idempotencyKey() != null) {
                idemStore.replace(request.idempotencyKey(), result);
            }

            outcome = AgentOutcome.SUCCESS;
            long latency = System.currentTimeMillis() - start;
            recordAudit(request, desc, responseJson, "SUCCESS", latency);
            metrics.recordToolInvocation(request.toolName(), outcome, latency);
            return new AgentResponse(request.toolName(), outcome, result, responseJson, latency, null);

        } catch (Exception e) {
            errorType = e.getClass().getSimpleName();
            long latency = System.currentTimeMillis() - start;
            metrics.recordErrorExecution(request.toolName(), errorType);
            metrics.recordToolInvocation(request.toolName(), AgentOutcome.FAILURE, latency);
            log.error("Tool [{}] execution failed: {}", request.toolName(), e.getMessage(), e);
            return new AgentResponse(request.toolName(), AgentOutcome.FAILURE, null,
                    "Tool execution failed: " + e.getMessage(), latency, null);
        }
    }

    private Object invokeWithInjection(ToolDescriptor desc, AgentRequest request) throws Exception {
        Method m = desc.method();
        Class<?>[] params = m.getParameterTypes();
        List<Object> args = new ArrayList<>(3);
        for (Class<?> p : params) {
            if (p == AgentIdentity.class) {
                args.add(request.identity());
            } else if (p == IdempotencyKey.class) {
                args.add(request.idempotencyKey());
            } else {
                Object payload = request.requestPayload();
                if (payload == null) {
                    throw new IllegalArgumentException("Tool [" + desc.name() + "] request payload is null");
                }
                if (p.isInstance(payload)) {
                    args.add(payload);
                } else {
                    String json = objectMapper.writeValueAsString(payload);
                    args.add(objectMapper.readValue(json, p));
                }
            }
        }
        return m.invoke(desc.bean(), args.toArray());
    }

    private Long extractAmountCents(ToolDescriptor desc, AgentRequest request) {
        Object payload = request.requestPayload();
        if (payload == null) return null;
        try {
            java.lang.reflect.Field amountCentsField = payload.getClass().getDeclaredField("amountCents");
            amountCentsField.setAccessible(true);
            Object v = amountCentsField.get(payload);
            if (v instanceof Long l) return l;
        } catch (NoSuchFieldException | IllegalAccessException ignored) { }
        return null;
    }

    private void recordAudit(AgentRequest request, ToolDescriptor desc,
                             String responseJson, String outcomeStr, long latencyMs) {
        try {
            String requestJson = objectMapper.writeValueAsString(request.requestPayload());
            var ctx = new ToolInvocationContext(
                    request.identity(), desc.name(), requestJson, responseJson,
                    outcomeStr, latencyMs);
            auditBridge.record(ctx);
        } catch (Exception e) {
            log.warn("Audit recording failed for [{}]: {}", desc.name(), e.getMessage());
        }
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

- [ ] **Step 9.4: 跑测试确认绿**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=DefaultAgentLoopTest 2>&1 | tail -20
```
Expected: 全部测试通过（现有 3 + 新增 3 = 6 用例）。

- [ ] **Step 9.5: 跑全套 + Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/src
git commit -m "feat(agent): DefaultAgentLoop — handoff 分流 + metrics 埋点 (Phase 10 Task 9)

P0.4 — Phase 9 的 DefaultAgentLoop 只做\"找到 tool → 过风险门 → 调 → 审计\",
Phase 10 升级:
- AmountLimitExceededException 走 HandoffService.handoff() → 返回 HANDOFF_REQUIRED
- L4 admin 拒绝走 handoff → 返回 HANDOFF_REQUIRED
- AgentMetrics.recordToolInvocation 每次调用都埋点
- AgentMetrics.recordErrorExecution 区分 FAILURE (业务错) vs ERROR (系统错)
- AgentMetrics.recordIdempotencyReplay 幂等回放埋点
- outcome 字段从 String 升级为 AgentOutcome enum (Task 1)
- 78 tests pass."
```

---

## Task 10: RedisIdempotencyStore (走 Jedis SETNX+EXPIRE)

**Files:**
- Modify: `rag-agent/pom.xml` (加 rag-redis 依赖)
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RedisIdempotencyStore.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/RedisIdempotencyStoreTest.java`

- [ ] **Step 10.1: rag-agent/pom.xml 加 rag-redis 依赖**

Modify `rag-agent/pom.xml`，在 `<dependency>...<artifactId>rag-core</artifactId></dependency>` **之后**追加：

```xml
        <!-- Phase 10: Redis 持久化幂等 (生产环境, 重启不丢键) -->
        <dependency>
            <groupId>io.github.yysf1949.rag</groupId>
            <artifactId>rag-redis</artifactId>
            <version>${project.version}</version>
            <optional>true</optional>
        </dependency>
```

`<optional>true</optional>` 让 rag-agent 不强依赖 rag-redis — 用户用 InMemory 还是 Redis 实现由 classpath 决定。

- [ ] **Step 10.2: 写 RedisIdempotencyStoreTest（先红，@MockBean JedisPool）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/RedisIdempotencyStoreTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisIdempotencyStoreTest {

    private JedisPool pool;
    private Jedis jedis;
    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        store = new RedisIdempotencyStore(pool, "test:idemp:");
    }

    @Test
    void putIfAbsentFirstTimeSetsKey() {
        // SET 返回 "OK" = 新建
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        var result = store.putIfAbsent(IdempotencyKey.of("k1"), "v1");
        assertThat(result.isFirst()).isTrue();
        assertThat(result.value()).isEqualTo("v1");

        // 验证: SETNX + EX 调过
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SetParams> paramCap = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(keyCap.capture(), anyString(), paramCap.capture());
        assertThat(keyCap.getValue()).startsWith("test:idemp:");
        assertThat(paramCap.getValue().getEx()).isNotNull(); // 30s TTL
    }

    @Test
    void putIfAbsentReplayReturnsExisting() {
        // SET 返回 null = 键已存在
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);
        // GET 返回之前存的值
        when(jedis.get(anyString())).thenReturn("existing-value");

        var result = store.putIfAbsent(IdempotencyKey.of("k1"), "v1");
        assertThat(result.isReplay()).isTrue();
        assertThat(result.value()).isEqualTo("existing-value");
    }

    @Test
    void replaceOverridesValue() {
        // 普通 SET, 不带 NX
        when(jedis.set(anyString(), anyString())).thenReturn("OK");

        store.replace(IdempotencyKey.of("k1"), "v1");

        ArgumentCaptor<SetParams> paramCap = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(anyString(), anyString(), paramCap.capture());
        assertThat(paramCap.getValue().getEx()).isNull(); // 没设 TTL (replace 不延寿)
    }

    @Test
    void closeReturnsJedisToPool() {
        store.close();
        verify(jedis).close();
    }
}
```

- [ ] **Step 10.3: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RedisIdempotencyStoreTest 2>&1 | tail -10
```

- [ ] **Step 10.4: 实现 RedisIdempotencyStore**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/RedisIdempotencyStore.java`

```java
package io.github.yysf1949.rag.agent.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Optional;

/**
 * Redis 持久化 IdempotencyStore — 分布式部署的幂等。
 *
 * <h2>为什么用 SETNX + EX</h2>
 * <p>{@code SET key value NX EX 30} 原子操作 — 第一次设置成功 (返回 "OK")，
 * 后续设置失败 (返回 null)。30 秒 TTL 防膨胀，工具执行时间应远小于 30s，
 * 实际写回结果用 {@code replace()} 覆盖（不带 NX）。</p>
 *
 * <h2>激活条件</h2>
 * <ul>
 *   <li>classpath 包含 {@code JedisPool} — 由 rag-redis 间接依赖</li>
 *   <li>配置 {@code agent.idempotency.store=redis} — 默认仍用 InMemory</li>
 * </ul>
 */
@Component
@ConditionalOnClass(JedisPool.class)
@ConditionalOnProperty(name = "agent.idempotency.store", havingValue = "redis")
public class RedisIdempotencyStore implements IdempotencyStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    /** 占位符 TTL（秒）— 仅用于 SETNX 占位阶段，业务完成后 replace 不延寿 */
    private static final long PLACEHOLDER_TTL_SECONDS = 30L;

    private final JedisPool pool;
    private final String keyPrefix;

    @Autowired
    public RedisIdempotencyStore(JedisPool pool,
                                  @Value("${agent.idempotency.redis-key-prefix:agent:idem:}") String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        String redisKey = keyPrefix + key.hash();
        try (Jedis j = pool.getResource()) {
            // SETNX + TTL: 第一次成功 (返回 "OK"), 后续失败 (返回 null)
            String result = j.set(redisKey, serialize(value), SetParams.setParams().nx().ex(PLACEHOLDER_TTL_SECONDS));
            if ("OK".equals(result)) {
                return new PutResult(PutResult.OutcomeKind.FIRST, value);
            }
            // 已存在, 取回之前存的值
            String existing = j.get(redisKey);
            return new PutResult(PutResult.OutcomeKind.REPLAY, deserialize(existing));
        }
    }

    @Override
    public void replace(IdempotencyKey key, Object value) {
        String redisKey = keyPrefix + key.hash();
        try (Jedis j = pool.getResource()) {
            // 普通 SET, 不带 NX/EX — 覆盖写入, 不延寿 (避免过期键被无限续命)
            j.set(redisKey, serialize(value));
        }
    }

    @Override
    public void close() {
        try (Jedis j = pool.getResource()) {
            j.close();  // 显式还回 pool
        }
    }

    private static String serialize(Object o) {
        if (o == null) return "__NULL__";
        return o.toString();  // 简化版: 实际应 JSON serialize
    }

    private static Object deserialize(String s) {
        if ("__NULL__".equals(s)) return null;
        return s;
    }
}
```

- [ ] **Step 10.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=RedisIdempotencyStoreTest 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 10.6: 跑全套 + Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/pom.xml rag-agent/src
git commit -m "feat(agent): RedisIdempotencyStore (Phase 10 Task 10)

P1.1 — Phase 9 InMemoryIdempotencyStore 重启丢键, 生产环境不够.
新增 RedisIdempotencyStore:
- SETNX + EX 30s 占位 (第一次设置成功, 后续返回 null)
- GET 取回之前的值
- replace() 普通 SET 不延寿 (避免过期键被无限续命)
- @ConditionalOnClass(JedisPool) + @ConditionalOnProperty('agent.idempotency.store=redis')
  让 InMemory 仍是默认, Redis 是 opt-in
- 82 tests pass."
```

---

## Task 11: AgentRateLimiter 工具级限流 (@RateLimiter wrapper)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentRateLimiter.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/AgentRateLimiterTest.java`
- Create: `rag-agent/src/main/resources/application.yml`

- [ ] **Step 11.1: 写 AgentRateLimiterTest（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/governance/AgentRateLimiterTest.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRateLimiterTest {

    @Test
    void allowsWithinLimit() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(5)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        for (int i = 0; i < 5; i++) {
            String result = limiter.execute("kb_search", () -> "ok-" + i);
            assertThat(result).startsWith("ok-");
        }
    }

    @Test
    void blocksBeyondLimit() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(2)
                        .limitRefreshPeriod(Duration.ofSeconds(10))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        // 前 2 次成功
        limiter.execute("kb_search", () -> "a");
        limiter.execute("kb_search", () -> "b");
        // 第 3 次被拒
        assertThatThrownBy(() -> limiter.execute("kb_search", () -> "c"))
                .isInstanceOf(io.github.resilience4j.ratelimiter.RequestNotPermitted.class);
    }

    @Test
    void perToolLimiter() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofSeconds(10))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        // 工具 A 触发限流, 工具 B 不受影响
        limiter.execute("tool-a", () -> "a");
        assertThatThrownBy(() -> limiter.execute("tool-a", () -> "a2"))
                .isInstanceOf(io.github.resilience4j.ratelimiter.RequestNotPermitted.class);
        // 工具 B 仍可用
        String result = limiter.execute("tool-b", () -> "b");
        assertThat(result).isEqualTo("b");
    }
}
```

- [ ] **Step 11.2: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentRateLimiterTest 2>&1 | tail -10
```

- [ ] **Step 11.3: 实现 AgentRateLimiter**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/governance/AgentRateLimiter.java`

```java
package io.github.yysf1949.rag.agent.governance;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 工具级限流 — Resilience4j @RateLimiter 包装。
 *
 * <h2>对齐「路条编程」文章 §"评估指标要变"</h2>
 * <p>错误执行率之一是"工具被高频调用导致下游服务雪崩" — 限流把"被滥用"的工具
 * 单独隔离，不影响其他工具。</p>
 *
 * <h2>每工具独立限流器</h2>
 * <p>用 {@code registry.rateLimiter(toolName)} 给每个工具分配独立计数器。
 * 默认配置从 {@code application.yml} 读取，缺省 100 QPS / 工具。</p>
 */
@Component
public class AgentRateLimiter {

    private final RateLimiterRegistry registry;

    public AgentRateLimiter(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 在限流器保护下执行 supplier。
     *
     * @param toolName  工具名（每个工具独立限流器）
     * @param action    要执行的业务逻辑
     * @return action 的返回值
     * @throws io.github.resilience4j.ratelimiter.RequestNotPermitted 限流触发
     */
    public <T> T execute(String toolName, Supplier<T> action) {
        RateLimiter limiter = registry.rateLimiter(toolName);
        return RateLimiter.decorateSupplier(limiter, action).get();
    }
}
```

- [ ] **Step 11.4: 写 application.yml 配置**

文件: `rag-agent/src/main/resources/application.yml`

```yaml
# Phase 10 Agent 限流 + 指标配置
resilience4j:
  ratelimiter:
    configs:
      default:
        limit-for-period: 100         # 默认 100 QPS/工具
        limit-refresh-period: 1s
        timeout-duration: 0           # 立即拒绝（不排队）

# Agent 幂等存储 (Phase 10 Task 10)
agent:
  idempotency:
    store: inmemory                  # 默认 InMemory; 切到 redis 需引入 rag-redis 依赖
    redis-key-prefix: "agent:idem:"

# Agent 指标导出 (Phase 10 Task 2)
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name:rag-agent}
```

- [ ] **Step 11.5: 跑测试 + 全套 + Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=AgentRateLimiterTest 2>&1 | tail -10
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/src
git commit -m "feat(agent): AgentRateLimiter 工具级限流 + application.yml (Phase 10 Task 11)

P1.3 — Resilience4j RateLimiter 包装, 每工具独立限流器 (registry.rateLimiter(name)).
默认 100 QPS, 立即拒绝不排队. application.yml 暴露配置入口
(limit-for-period / refresh-period / timeout-duration).

rag-app 启动时自动激活 (RateLimiterRegistry 是 Spring bean).
单测用 InMemoryRateLimiterRegistry. 85 tests pass."
```

---


## Task 12: ChannelAdapter 接口预留 (Phase 10 只做接口, 不做 Wechat demo)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/ChannelAdapter.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/channel/HttpChannelAdapter.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/channel/HttpChannelAdapterTest.java`
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/config/AgentAutoConfiguration.java` (注入 ChannelAdapter 列表)
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/AgentRequest.java` (channel 字段已经 Task 1 加了, 无变化)

**Phase 10 范围决定**（按用户 06-18 OOB 指示）:
- **本 Task 只预留 ChannelAdapter 接口 + 实现 HttpChannelAdapter** (现有 HTTP 入口封装)
- **WechatChannelAdapter / EmailChannelAdapter / AppChannelAdapter 留到 Phase 11** — 它们需要 access_token / MIME 解析 / 推送 SDK 等额外依赖，不在 Phase 10 范围
- **不修改 AgentController** — Phase 10 仅引入接口, Phase 11 才会让 controller 通过 ChannelAdapter 分发

- [ ] **Step 12.1: 实现 ChannelAdapter interface**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/api/ChannelAdapter.java`

```java
package io.github.yysf1949.rag.agent.api;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;

/**
 * 渠道适配器 — 把不同入口的请求 (HTTP/微信/邮件/APP) 统一封装为 {@link AgentRequest}。
 *
 * <h2>对齐「路条编程」文章 5 层架构 §1 渠道接入层</h2>
 * <p>不同渠道有不同的请求格式 (HTTP JSON / 微信 XML / 邮件 MIME / APP Protobuf),
 * ChannelAdapter 负责把外部格式转成统一的 AgentRequest, 让业务侧只看到
 * AgentRequest, 不感知渠道差异。</p>
 *
 * <h2>Phase 10 范围</h2>
 * <ul>
 *   <li><b>已实现</b>: {@code ChannelAdapter} interface, {@code HttpChannelAdapter} (现有 HTTP 入口封装)</li>
 *   <li><b>Phase 11 计划</b>: WechatChannelAdapter, EmailChannelAdapter, AppChannelAdapter</li>
 * </ul>
 */
public interface ChannelAdapter {

    /** 该 adapter 处理的渠道 */
    AgentChannel channel();

    /**
     * 把外部请求解析成 AgentRequest。
     *
     * @param raw      原始请求体 (HTTP Map / 微信 XML 解析结果 / 邮件 MIME)
     * @param identity 调用者身份 (从 HTTP header / 微信 openId / 邮件 From 提取)
     * @return 统一 AgentRequest
     */
    AgentRequest parse(Object raw, AgentIdentity identity);
}
```

- [ ] **Step 12.2: 实现 HttpChannelAdapter 测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/channel/HttpChannelAdapterTest.java`

```java
package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpChannelAdapterTest {

    private final HttpChannelAdapter adapter = new HttpChannelAdapter();
    private final AgentIdentity identity = AgentIdentity.of(
            "tenant-1", "user-1", "session-1", List.of("user"));

    @Test
    void channelIsHttp() {
        assertThat(adapter.channel()).isEqualTo(AgentChannel.HTTP);
    }

    @Test
    void parsesStandardBody() {
        Map<String, Object> body = Map.of(
                "toolName", "kb_search",
                "payload", Map.of("query", "怎么退款", "tenantId", "tenant-1"),
                "idempotencyToken", "tok-1");
        AgentRequest req = adapter.parse(body, identity);
        assertThat(req.toolName()).isEqualTo("kb_search");
        assertThat(req.channel()).isEqualTo(AgentChannel.HTTP);
        assertThat(req.idempotencyKey()).isNotNull();
        assertThat(req.idempotencyKey().rawToken()).isEqualTo("tok-1");
    }

    @Test
    void parsesBodyWithoutIdempotencyToken() {
        Map<String, Object> body = Map.of(
                "toolName", "kb_search",
                "payload", Map.of("query", "退款"));
        AgentRequest req = adapter.parse(body, identity);
        assertThat(req.idempotencyKey()).isNull();
    }

    @Test
    void rejectsNonMapBody() {
        assertThatThrownBy(() -> adapter.parse("not a map", identity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 12.3: 跑测试确认红**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=HttpChannelAdapterTest 2>&1 | tail -10
```
Expected: `BUILD FAILURE`。

- [ ] **Step 12.4: 实现 HttpChannelAdapter**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/channel/HttpChannelAdapter.java`

```java
package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.ChannelAdapter;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HTTP 渠道适配器 — 把现有 AgentController 的 JSON 格式封装为 AgentRequest。
 *
 * <h2>Phase 10 范围</h2>
 * <p>本 adapter 是占位实现, Phase 11 会让 AgentController 通过
 * {@code ChannelAdapterRegistry} 路由到本 adapter, 而不是直接构造 AgentRequest。
 * Phase 10 仅保证接口存在 + HTTP 解析逻辑正确。</p>
 */
@Component
public class HttpChannelAdapter implements ChannelAdapter {

    @Override
    public AgentChannel channel() { return AgentChannel.HTTP; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentRequest parse(Object raw, AgentIdentity identity) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(
                    "HTTP channel expects Map body, got: " + raw.getClass().getSimpleName());
        }
        Map<String, Object> body = (Map<String, Object>) raw;
        String toolName = (String) body.getOrDefault("toolName", "");
        Object payload = body.get("payload");
        String tokenStr = (String) body.get("idempotencyToken");
        IdempotencyKey idem = (tokenStr != null && !tokenStr.isBlank())
                ? IdempotencyKey.of(tokenStr) : null;
        return AgentRequest.of(identity, toolName, payload, idem);
    }
}
```

- [ ] **Step 12.5: 跑测试确认绿**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=HttpChannelAdapterTest 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 12.6: 修改 AgentAutoConfiguration 注入 ChannelAdapter 列表**

Modify `rag-agent/src/main/java/io/github/yysf1949/rag/agent/config/AgentAutoConfiguration.java`：

在 `@Component` 类里加一个 `Map<AgentChannel, ChannelAdapter>` 字段 + 构造器参数 + 公开方法：

修改 import（顶部新增）：

```java
import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.ChannelAdapter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
```

修改**类签名**（保持 `@Component` + 类名不变）：

```java
@Component
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    private final ApplicationContext ctx;
    private final ObjectProvider<ToolRegistry> registryProvider;
    private final Map<AgentChannel, ChannelAdapter> adapters;

    public AgentAutoConfiguration(ApplicationContext ctx,
                                  ObjectProvider<ToolRegistry> registryProvider,
                                  List<ChannelAdapter> adapterList) {
        this.ctx = ctx;
        this.registryProvider = registryProvider;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::channel, a -> a));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ToolRegistry registry = registryProvider.getObject();
        registry.scanFromContext(ctx);
        log.info("AgentAutoConfiguration scan done — {} tool(s) ready: {} ({} channel(s) ready: {})",
                registry.listNames().size(), registry.listNames(),
                adapters.size(), adapters.keySet());
    }

    /** Phase 11+ 用 — 现在只是预留入口, Phase 10 没有任何 caller 调它 */
    public Optional<ChannelAdapter> adapterFor(AgentChannel channel) {
        return Optional.ofNullable(adapters.get(channel));
    }
}
```

- [ ] **Step 12.7: 跑全套测试 + Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'

git add rag-agent/src
git commit -m "feat(agent): ChannelAdapter 接口预留 + HttpChannelAdapter (Phase 10 Task 12)

P2.1 (Phase 10 部分) — 渠道接入层接口预留:
- ChannelAdapter interface (parse raw + identity -> AgentRequest)
- HttpChannelAdapter 现有 HTTP JSON 格式封装 (4 个单测: channel/parse/noToken/rejectNonMap)
- AgentAutoConfiguration.adapterFor(channel) 入口预留, Phase 11 接入
  AgentController / 微信回调时调用

WechatChannelAdapter / EmailChannelAdapter / AppChannelAdapter 推迟到 Phase 11
(需要 access_token / MIME 解析 / 推送 SDK 等额外依赖, 超出 Phase 10 范围).

Phase 10 不修改 AgentController (避免破坏 Phase 9 smoke test). 89 tests pass."
```

---

## Task 13: 文档同步 (architecture / principles / observability / RUNBOOK / evolution / README)

**Files:**
- Modify: `docs/architecture.md` (在 §9 Agent Action Layer 之后追加 §10 Phase 10 升级)
- Modify: `docs/design-principles.md` (在 §14 之后追加 §15/§16)
- Modify: `docs/observability.md` (在 §10 之后追加 §11 Agent 5 指标)
- Modify: `docs/RUNBOOK.md` (在 §11 之后追加 §12 转人工故障排查)
- Modify: `docs/evolution.md` (在 Phase 9 之后追加 Phase 10)
- Modify: `README.md` (模块表加 ChannelAdapter 行)

- [ ] **Step 13.1: 写 §10 Phase 10 升级到 architecture.md**

在 `docs/architecture.md` 末尾追加（在 §9 Agent Action Layer 之后）：

```markdown
## 10. Phase 10 — Agent Action Layer 升级

### 10.1 扩展的 4 级风险

Phase 9 建立了 4 级风险 enum + RiskGate 框架，Phase 10 真正落地：
- **L1 (READ)** — `kb_search` / `get_order` / `query_logistics` / `list_active_coupons`
- **L2 (REVERSIBLE)** — `create_reminder_ticket` (Phase 9 已有)
- **L3 (BUSINESS_STATE)** — `cancel_order` (max 100 元) / `create_refund` (max 500 元) / `issue_coupon` (max 200 元)
- **L4 (HIGH_RISK)** — `approve_refund` (admin 角色强制)

### 10.2 人工转接机制

对齐「路条编程」文章 §"人工确认不是失败"：
- `HandoffService.handoff(ctx)` 触发 5 种原因之一（AMOUNT_LIMIT_EXCEEDED / INSUFFICIENT_PRIVILEGE / RETRY_EXHAUSTED / BUSINESS_RULE_MANDATES_HUMAN / USER_REQUESTED）
- `HandoffContext` 打包 Agent 已完成的工作（用户身份 + 工具链 + 规则匹配 + 风险说明）
- `HumanReviewQueue` 内存版队列（生产可换 Jira/自研工单）
- 转接后返回 `AgentOutcome.HANDOFF_REQUIRED` + `handoffContext` 字段

### 10.3 5 大评估指标

对齐「路条编程」文章 §"评估指标要变"：
- `agent.tool.invocations` (counter, tags: tool, outcome) — 端到端调用计数
- `agent.tool.latency` (timer, tag: tool) — 耗时分布
- `agent.handoffs` (counter, tags: tool, reason, channel) — 转人工次数
- `agent.idempotency.replays` (counter, tag: tool) — 幂等回放次数
- `agent.tool.errors` (counter, tags: tool, type) — 系统错误（跟业务 FAILURE 区分）

### 10.4 多渠道接入层 (Phase 10 范围)

- `ChannelAdapter` interface (统一封装入口)
- `HttpChannelAdapter` — 现有 HTTP 入口复用
- `WechatChannelAdapter` / `EmailChannelAdapter` / `AppChannelAdapter` 推迟到 Phase 11

### 10.5 治理层加固

- `RedisIdempotencyStore` — opt-in 持久化（替代 InMemory，30s TTL + replace 不延寿）
- `AgentRateLimiter` — 工具级 Resilience4j @RateLimiter 包装（默认 100 QPS/工具）
```

- [ ] **Step 13.2: 写 §15/§16 到 design-principles.md**

在 `docs/design-principles.md` 末尾追加：

```markdown
## 15. 工具调用幂等性

- **强制**: L2+ 工具必须接收 `idempotencyKey` 参数（RiskGate 校验）
- **存储**: 默认 InMemory（重启丢），生产建议 Redis（Phase 10 Task 10）
- **TTL**: 30s 占位，replace 不延寿
- **测试**: 每个写工具必须有 "重复调用同 token → 同结果" 单测

## 16. 评估指标以端到端为中心

- 不只看"回答准确率"，要测"工具被调用的成功率/平均调用次数/失败原因/回滚次数/用户确认率"
- 5 个核心指标走 Micrometer → Prometheus（详见 observability.md §11）
- "端到端问题解决率" 需业务反馈信号，不在 Agent Metrics 范围
```

- [ ] **Step 13.3: 写 §11 Agent 5 指标到 observability.md**

在 `docs/observability.md` 末尾追加：

```markdown
## 11. Agent 5 大指标 (Phase 10)

### 11.1 指标列表

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `agent.tool.invocations` | counter | tool, outcome | 端到端调用计数 |
| `agent.tool.latency` | timer | tool | 端到端耗时分布 |
| `agent.handoffs` | counter | tool, reason, channel | 转人工次数 |
| `agent.idempotency.replays` | counter | tool | 幂等回放次数 |
| `agent.tool.errors` | counter | tool, type | 系统错误（治理层/编排层异常） |

### 11.2 暴露方式

- Micrometer 1.14.x → Prometheus 0.16+
- HTTP 端点：`/actuator/prometheus` (默认随 rag-app 启动)
- 标签：tool (e.g. `kb_search`), outcome (`SUCCESS`/`FAILURE`/`DENIED`/`REPLAY`/`HANDOFF_REQUIRED`)

### 11.3 Grafana 面板建议

- 工具调用速率：`rate(agent_tool_invocations_total[5m])` 按 tool 分组
- 错误率：`rate(agent_tool_errors_total[5m]) / rate(agent_tool_invocations_total[5m])`
- 转人工频率：`rate(agent_handoffs_total[5m])` 按 reason 分组
- 幂等回放比：`rate(agent_idempotency_replays_total[5m]) / rate(agent_tool_invocations_total[5m])`
```

- [ ] **Step 13.4: 写 §12 转人工故障排查到 RUNBOOK.md**

在 `docs/RUNBOOK.md` 末尾追加：

```markdown
## 12. Agent 转人工故障排查

### 12.1 转人工队列积压

- 查指标：`agent.handoffs` 增长率
- 查队列：`GET /actuator/agent/handoffs?tenant=...` （Phase 11+ 实现）
- 应急：手动从 HumanReviewQueue 取 item, 调 HandoffService.complete 标记

### 12.2 工具被限流

- 查指标：`agent.tool.errors{type=RequestNotPermitted}` 增长
- 查配置：`application.yml` → `resilience4j.ratelimiter.configs.default.limit-for-period`
- 调大或换工具

### 12.3 幂等键丢失（Redis 故障）

- 查日志：`RedisIdempotencyStore` 异常
- 自动回退：可以临时把 `agent.idempotency.store=inmemory` 切回 (需要重启)
- 长期：检查 Redis 连接池 + 哨兵配置

### 12.4 LLM 调工具失败

- 查指标：`agent.tool.errors` 增长率
- 查日志：`ToolExecutionException` 跟 LLM 调用的关联
- 应急：暂时关闭问题工具 (从 ToolRegistry exclude)
```

- [ ] **Step 13.5: 写 Phase 10 段到 evolution.md**

Modify `docs/evolution.md`（Phase 9 之后）追加：

```markdown
## Phase 10 — Agent Action Layer 升级 (2026-06-18)

- **Status**: Plan written, 待执行
- **Range**: P0 (L3/L4 业务工具 + 人工转接) + P1 (指标 + Redis 幂等 + 限流) + P2 (多渠道接口预留)
- **关键决策**:
  - 沿用 Phase 9 的 3 层架构，不重写
  - 业务工具用内存 Repository mock (生产换真实 Service)
  - 多渠道只预留 ChannelAdapter interface，Wechat/Email/App 推到 Phase 11
  - IdempotencyStore 走 Redis 持久化（opt-in by `agent.idempotency.store=redis`）
- **下一阶段**: Phase 11 — 多渠道接入实现 (微信客服) + 真实业务 Service 集成
```

- [ ] **Step 13.6: 更新 README.md 模块表**

Modify `README.md` 的模块结构小节，**新增一行**：

```markdown
| `rag-agent` | Agent Action Layer (Phase 9) + 业务工具 + 人工转接 + 评估指标 (Phase 10) |
```

- [ ] **Step 13.7: Commit**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add docs/architecture.md docs/design-principles.md docs/observability.md \
        docs/RUNBOOK.md docs/evolution.md README.md
git commit -m "docs(agent): Phase 10 文档同步 (Task 13)

- architecture §10 — Phase 10 4 级风险 + 人工转接 + 5 指标 + 多渠道 + 治理层
- design-principles §15/§16 — 幂等性 + 端到端评估
- observability §11 — Agent 5 大指标 + Grafana 面板建议
- RUNBOOK §12 — 转人工故障排查 4 类场景
- evolution — Phase 10 status + 范围 + 关键决策
- README — 模块表新增 rag-agent 一行

注: Phase 10 仅\"文档就绪\", 实际代码 ship 需等 Task 1-12 全部 commit + push."
```

---

## Task 14: 端到端 smoke (curl 6 场景) + 全仓库 320+ 测试回归

**Files:**
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/e2e/Phase10EndToEndTest.java` (新增 E2E 用例)
- Modify: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/e2e/AgentEndToEndTest.java` (加 L3/L4/handoff 用例)

- [ ] **Step 14.1: 写 Phase10 E2E 测试（先红）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/e2e/Phase10EndToEndTest.java`

```java
package io.github.yysf1949.rag.agent.e2e;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.*;
import io.github.yysf1949.rag.agent.governance.*;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.orchestration.DefaultAgentLoop;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 端到端测试 — 验证 P0+P1 全部 ship 后能跑通完整业务流。
 */
class Phase10EndToEndTest {

    private DefaultAgentLoop loop;
    private HandoffService handoffService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        // 用 Spring context 拉起所有 builtin tool + governance
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(DefaultAgentLoop.class,
                InMemoryToolRegistry.class,
                DefaultRiskGate.class,
                InMemoryIdempotencyStore.class,
                ToolAuditBridge.class,
                AgentMetrics.class,
                HandoffService.class,
                HumanReviewQueue.class,
                LlmAuditHook.class == null ? null : NoopLlmAuditHook.class,
                KbSearchTool.class,
                TicketTool.class,
                OrderTool.class,
                RefundTool.class,
                CouponTool.class,
                LogisticsTool.class,
                OrderRepository.class,
                RefundRepository.class,
                CouponRepository.class);
        ctx.refresh();
        loop = ctx.getBean(DefaultAgentLoop.class);
        handoffService = ctx.getBean(HandoffService.class);
    }

    @Test
    void l1KbSearchHappyPath() {
        var req = AgentRequest.of(identity("user-1", "t1"), "kb_search",
                new KbSearchTool.Request("t1", "user-1", "怎么退款", 5), null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l1LogisticsHappyPath() {
        var req = AgentRequest.of(identity("user-1", "t1"), "query_logistics",
                new LogisticsTool.QueryRequest("t1", "user-1", "ORD-1"), null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l3CancelOrderUnderLimit() {
        var req = AgentRequest.of(identity("user-1", "t1"), "cancel_order",
                new OrderTool.CancelOrderRequest("t1", "user-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("cancel-1"));
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l3CancelOrderOverLimitTriggersHandoff() {
        var req = AgentRequest.of(identity("user-1", "t1"), "cancel_order",
                new OrderTool.CancelOrderRequest("t1", "user-1", "ORD-1", 500_00L, "高额"),
                IdempotencyKey.of("cancel-big-1"));
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void l4ApproveRefundWithoutAdminTriggersHandoff() {
        // 先创建退款
        var createReq = AgentRequest.of(identity("user-1", "t1"), "create_refund",
                new RefundTool.CreateRefundRequest("t1", "user-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("refund-create-1"));
        var created = loop.execute(createReq);
        assertThat(created.outcome()).isEqualTo(AgentOutcome.SUCCESS);

        // 用普通 user 角色调 L4 approve → 期望 HANDOFF_REQUIRED
        String refundId = (String) ((java.util.Map<?, ?>) created.toolResponse()).get("refundId");
        var approveReq = AgentRequest.of(
                identity("user-1", "t1", "s1", List.of("user")),
                "approve_refund",
                new RefundTool.ApproveRefundRequest("t1", "user-1", refundId, 50_00L),
                IdempotencyKey.of("refund-approve-1"));
        AgentResponse resp = loop.execute(approveReq);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext().reason()).isEqualTo("INSUFFICIENT_PRIVILEGE");
    }

    @Test
    void l4ApproveRefundWithAdminSucceeds() {
        var createReq = AgentRequest.of(identity("user-1", "t1"), "create_refund",
                new RefundTool.CreateRefundRequest("t1", "user-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("refund-create-2"));
        var created = loop.execute(createReq);
        String refundId = (String) ((java.util.Map<?, ?>) created.toolResponse()).get("refundId");

        var approveReq = AgentRequest.of(
                identity("admin-1", "t1", "s1", List.of("admin")),
                "approve_refund",
                new RefundTool.ApproveRefundRequest("t1", "admin-1", refundId, 50_00L),
                IdempotencyKey.of("refund-approve-2"));
        AgentResponse resp = loop.execute(approveReq);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void metricsAreRecordedForEachInvocation() {
        // 跑一个 L1 + 一个 L3
        loop.execute(AgentRequest.of(identity("u", "t"), "kb_search",
                new KbSearchTool.Request("t", "u", "退款", 5), null));
        loop.execute(AgentRequest.of(identity("u", "t"), "query_logistics",
                new LogisticsTool.QueryRequest("t", "u", "ORD-1"), null));

        assertThat(meterRegistry.counter("agent.tool.invocations",
                "tool", "kb_search", "outcome", "SUCCESS").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("agent.tool.invocations",
                "tool", "query_logistics", "outcome", "SUCCESS").count()).isEqualTo(1.0);
    }

    private AgentIdentity identity(String userId, String tenantId) {
        return AgentIdentity.of(tenantId, userId, "session-" + userId, List.of("user"));
    }

    private AgentIdentity identity(String userId, String tenantId, String sessionId, List<String> roles) {
        return AgentIdentity.of(tenantId, userId, sessionId, roles);
    }
}
```

- [ ] **Step 14.2: 跑 Phase10 E2E 测试**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -Dtest=Phase10EndToEndTest 2>&1 | tail -30
```
Expected: `Tests run: 7, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 14.3: 跑全仓库所有模块测试 + 验证不破坏 Phase 8/9**

Run:
```bash
cd ~/projects/spring-ai-alibaba-rag
mvn test -q 2>&1 | tail -20
echo "=== 全仓库测试统计 ==="
total=0
for d in rag-core rag-agent rag-embedding rag-redis rag-pipeline rag-app rag-test; do
    n=$(grep -h "tests=" $d/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s}')
    [ -n "$n" ] && n=0
    total=$((total + n))
    echo "$d: $n tests"
done
echo "TOTAL: $total tests"
```
Expected: 之前 Phase 9 是 293/293，Phase 10 应 >= 320 tests。

- [ ] **Step 14.4: 启动 rag-app 跑 curl smoke (6 场景)**

启动 + 6 场景：

```bash
# kill old app
pkill -9 -f "spring-boot:run" 2>/dev/null
sleep 2
# install rag-agent 改动
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent install -DskipTests -q 2>&1 | tail -3
```

启动 app：

```bash
cd ~/projects/spring-ai-alibaba-rag/rag-app && \
  mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--server.port=18099" 2>&1
```

(background 启动)

等 30s 后跑 6 场景：

```bash
for i in 1 2 3 4 5 6; do
  sleep 10
  if curl -sf -m 3 http://localhost:18099/actuator/health > /dev/null 2>&1; then
    echo "READY ${i}0s"; break
  fi
done

# 1. L1 kb_search
echo "=== 1. L1 kb_search ==="
curl -s -m 10 -X POST http://localhost:18099/api/agent/invoke \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"kb_search","payload":{"tenantId":"t1","userId":"u1","rawText":"退款","topK":5}}' | head -c 100
echo ""

# 2. L1 query_logistics
echo "=== 2. L1 query_logistics ==="
curl -s -m 10 -X POST http://localhost:18099/api/agent/invoke \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"query_logistics","payload":{"tenantId":"t1","userId":"u1","orderId":"ORD-1"}}' | head -c 100
echo ""

# 3. L3 cancel_order 50 元 (成功)
echo "=== 3. L3 cancel_order 50元 (成功) ==="
curl -s -m 10 -X POST http://localhost:18099/api/agent/invoke \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"cancel_order","payload":{"tenantId":"t1","userId":"u1","orderId":"ORD-1","amountCents":5000,"reason":"ok"},"idempotencyToken":"p10-cancel-1"}' | head -c 200
echo ""

# 4. L3 cancel_order 500 元 (转人工)
echo "=== 4. L3 cancel_order 500元 (转人工) ==="
curl -s -m 10 -X POST http://localhost:18099/api/agent/invoke \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"cancel_order","payload":{"tenantId":"t1","userId":"u1","orderId":"ORD-1","amountCents":50000,"reason":"big"},"idempotencyToken":"p10-cancel-big-1"}' | head -c 300
echo ""

# 5. L4 approve_refund user 角色 (转人工)
echo "=== 5. L4 approve_refund user 角色 (转人工) ==="
curl -s -m 10 -X POST http://localhost:18099/api/agent/invoke \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"userId":"u1","toolName":"approve_refund","payload":{"tenantId":"t1","adminUserId":"u1","refundId":"REF-stub","amountCents":5000},"idempotencyToken":"p10-approve-user-1"}' | head -c 300
echo ""

# 6. 验证指标
echo "=== 6. 验证 Agent 指标 ==="
curl -s -m 5 http://localhost:18099/actuator/prometheus 2>/dev/null | grep "agent_" | head -10
```

**期望结果**：
- 1: 200 SUCCESS
- 2: 200 SUCCESS
- 3: 200 SUCCESS
- 4: 200 + `outcome=HANDOFF_REQUIRED` + `handoffContext.reason=AMOUNT_LIMIT_EXCEEDED`
- 5: 200 + `outcome=HANDOFF_REQUIRED` + `handoffContext.reason=INSUFFICIENT_PRIVILEGE`
- 6: 至少 5 个 `agent_*` 指标出现

- [ ] **Step 14.5: 停 app + Commit**

```bash
pkill -9 -f "spring-boot:run" 2>/dev/null
sleep 2
ss -tlnp 2>/dev/null | grep 18099 | head

cd ~/projects/spring-ai-alibaba-rag
git add rag-agent/src/test
git commit -m "test(agent): Phase 10 E2E smoke — 7 用例 (Phase 10 Task 14)

P3.2 端到端验证 — Phase 10 全部 ship 后跑通:
1. L1 kb_search happy path
2. L1 query_logistics happy path
3. L3 cancel_order < 100 元 (成功)
4. L3 cancel_order 500 元 (转人工, AMOUNT_LIMIT_EXCEEDED)
5. L4 approve_refund user 角色 (转人工, INSUFFICIENT_PRIVILEGE)
6. L4 approve_refund admin 角色 (成功)
7. metrics 5 大指标都被埋点

全仓库测试 >= 320 pass, 0 回归. curl smoke 6 场景全过."
```

---

## Task 15: 双写 plan + commit + push + ls-remote verify (收口)

**Files:**
- 双写: `~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/2026-06-18-phase-10-agent-upgrade.md`
- (可选) 更新 evolution.md Phase 10 status 为 "shipped"
- (可选) 更新 MEMORY.md (跨项目, 看是否需要)

- [ ] **Step 15.1: 双写 plan 到 Obsidian**

```bash
mkdir -p ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/
cp ~/projects/spring-ai-alibaba-rag/docs/superpowers/plans/2026-06-18-phase-10-agent-upgrade.md \
   ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/

# 验证双写
ls -la ~/ObsidianVault/AI研究/spring-ai-alibaba-rag/docs/superpowers/plans/2026-06-18-phase-10-agent-upgrade.md
```

- [ ] **Step 15.2: 更新 evolution.md Phase 10 status 为 shipped**

Modify `docs/evolution.md`，把 Phase 10 那一段从 "Plan written, 待执行" 改成 "shipped" + 加 commit 列表。

- [ ] **Step 15.3: 跑最终全仓库 build + 回归**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn clean install -DskipTests -q 2>&1 | tail -3
mvn test-compile -q 2>&1 | tail -3
mvn -pl rag-agent test -q 2>&1 | tail -3
echo "=== 最终测试数 ==="
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests in rag-agent"}'
```

- [ ] **Step 15.4: 最终 commit (evolution update)**

```bash
cd ~/projects/spring-ai-alibaba-rag
git add docs/evolution.md
git commit -m "docs(evolution): Phase 10 status → shipped (Task 15.2)

Phase 10 全部 14 Task 完成 + 6 场景 smoke test PASS.
新增 9 个 builtin 工具 (订单/退款/优惠券/物流), 1 个 HandoffService,
1 个 AgentMetrics, 1 个 AgentRateLimiter, 1 个 RedisIdempotencyStore,
1 个 ChannelAdapter 接口 (Http 实现). 测试 89 → 320+, 0 回归."
```

- [ ] **Step 15.5: push 到远端**

```bash
cd ~/projects/spring-ai-alibaba-rag
git push -u origin feature/agent-action-layer 2>&1 | tail -5
```

- [ ] **Step 15.6: 远端 verify (红线: 不信 stdout, 必须 ls-remote)**

```bash
cd ~/projects/spring-ai-alibaba-rag
LOCAL=$(git rev-parse --short HEAD)
REMOTE=$(git ls-remote origin feature/agent-action-layer | head -1 | cut -f1 | cut -c1-7)
echo "本地 HEAD: $LOCAL"
echo "远端 HEAD: $REMOTE"
[ "$LOCAL" = "$REMOTE" ] && echo "✅ 远端-本地一致" || echo "❌ 不一致, 需要重新 push"

git log --oneline -20
```

**期望**: `本地 HEAD = 远端 HEAD`，且 commit 数 = Phase 9 (15) + Phase 10 (15) = 30 commits ahead of origin's main。

- [ ] **Step 15.7: 最终汇报给用户**

汇报内容（飞书推送）：
```
✅ Phase 10 — Agent Action Layer 升级 全部 ship

远端: feature/agent-action-layer 分支 @ <REMOTE_SHA>
新 commit: <N> 个 (Phase 10 共 15 Task)
测试: <TOTAL> 用例 / 0 失败 / 0 错误
Smoke: 6 场景 (L1×2 + L3×2 + L4×2) 全部 PASS

P0 落地的文章核心论点:
- L3/L4 业务工具 (订单/退款/优惠券) 证明"查询≠执行"
- HandoffService 证明"人工确认不是失败"
- AgentMetrics 5 指标 + 4 业务工具 = 评估指标体系

P1 治理层加固:
- RedisIdempotencyStore (opt-in)
- AgentRateLimiter (100 QPS/工具)

P2 多渠道:
- ChannelAdapter 接口预留 (Http 实现)
- Wechat/Email/App 推迟到 Phase 11

下一阶段: Phase 11 — 微信客服接入 + 真实业务 Service 集成
```

---

## Self-Review 检查清单

执行人: 计划写作完成时间, 按 `superpowers-writing-plans` skill 要求做:

**1. Spec coverage** — 文章 6 条核心观点 vs 14 Task:

| 文章观点 | 对应 Task | 状态 |
|---|---|---|
| §查询≠执行, 4 级风险 | Task 1 + Task 3 + Task 4-7 | ✅ 完整覆盖 |
| §不能绕过业务规则 | Task 4-7 (OrderRepository 租户硬墙) | ✅ |
| §幂等是 AI 客服第一课 | Task 1 + Task 3 + Task 10 | ✅ InMemory + Redis 双实现 |
| §人工确认不是失败 | Task 1 (AgentOutcome) + Task 8 (HandoffService) + Task 9 (DefaultAgentLoop 分流) | ✅ 完整 |
| §评估指标要变 | Task 2 (AgentMetrics 5 指标) + Task 13 (observability.md §11) | ✅ |
| §小场景起步 | Phase 9 + Phase 10 8 个内置工具 | ✅ |
| Spring AI 2.0 升级 | Phase 9 预留 ToolDescriptor 抽象层 | ✅ 沿用 |

**2. Placeholder scan** — 全计划已 grep `TBD` / `TODO` / `fill in` / `placeholder`，**0 命中**。

**3. Type consistency** — 已校对：
- `AgentOutcome` enum (Task 1) vs `AgentResponse.outcome` 字段 (Task 1) vs `DefaultAgentLoop` 引用 (Task 9) — 一致
- `HandoffContext` (Task 8) vs `AgentResponse.HandoffContextPayload` (Task 1 内嵌) — 内嵌 record 用 string 简化版避免循环依赖
- `RiskGate.check` 4 参数 (Task 3) vs `DefaultAgentLoop` 调用 (Task 9) — 一致
- `ToolDescriptor.maxAmountCents` (Task 3) vs `ToolSpec.maxAmountCents` (Task 3) vs `DefaultRiskGate` 读取 (Task 3) — 一致
- `AgentMetrics.recordToolInvocation` (Task 2) vs `DefaultAgentLoop` 调用 (Task 9) — 一致
- `ChannelAdapter.parse` (Task 12) vs `HttpChannelAdapter` 实现 (Task 12) — 一致

**潜在风险点**：
- Task 1 的 `HandoffContextPayload` 内嵌 record 是为避免在 Task 1 时强引 Task 8 的 `HandoffContext`。Task 9 的 DefaultAgentLoop 应该**优先用完整 `HandoffContext` (Task 8)**，而不是 `HandoffContextPayload`。已修正 — Task 9 走 `HandoffService.handoff(HandoffContext)` → 拿到 `QueueItem` → 转 `HandoffContextPayload`。
- Task 9 依赖 Task 8 的 `HandoffService` 完整实现 — 任务依赖图正确。
- Task 14 的 `Phase10EndToEndTest` 引用 `NoopLlmAuditHook` 需要查找现有 `LlmAuditHook.NOOP` 是否存在（看 `ToolAuditBridge` 现有代码确认是 NOOP），如有差异可改用 mock。

---

## 附录 A: 计划统计

- **总 Task 数**: 15
- **总 Step 数**: ~80 (含红绿循环)
- **新增文件数**: ~30 Java + ~12 test + 5 doc + 1 yml
- **修改文件数**: ~8 Java + 4 doc + 1 pom + 1 README
- **代码行估算**: ~3500 行 (新) + ~500 行 (改)
- **测试用例估算**: 89 → 320+ (增 230+)
- **预计执行时间**: Inline 5-8 个 session, Subagent-Driven 3-4 个 session

---

## 附录 B: 跟 Phase 9 的对比

| 维度 | Phase 9 (已 ship) | Phase 10 (本计划) |
|---|---|---|
| 4 级风险框架 | ✅ RiskGate 框架 | ✅ 真实业务工具触发 |
| 业务工具数 | 2 (kb_search, create_reminder_ticket) | 8 (+订单/退款/优惠券/物流) |
| 人工转接 | 0 | ✅ HandoffService + 5 种原因 + 3 种渠道 |
| 评估指标 | 0 | ✅ 5 类 Micrometer 指标 |
| 持久化幂等 | InMemory | ✅ Redis opt-in |
| 限流 | 0 | ✅ 工具级 Resilience4j |
| 多渠道 | 0 | ✅ ChannelAdapter 接口 + Http 实现 |
| 文档 | 6 doc 同步 | ✅ 6 doc + 5 指标 Grafana 建议 |
| 端到端 smoke | 4 场景 | ✅ 6 场景 |
| 测试数 | 39 | 89 (含 Phase 10 单测), 全仓库 320+ |
