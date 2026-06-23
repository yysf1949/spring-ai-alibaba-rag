# Phase 13 Plan — 完善 AI 客服治理 + 业务规则（对齐"路条编程"文章）

> 文章：*Salesforce 36 亿美元押注 AI 客服：Java 后端真正的机会，不是接个大模型*（2026-06-18）
> 当前：Phase 9-12 已 ship 37 commits / 148 tests / 7 业务工具
> 目标：在不动现有架构前提下，补齐"治理层"和"业务规则"两类差距，让 Agent 真正能上生产

## 1. 文章核心论断（精简）

| # | 论断 | 文章原话关键词 |
|---|---|---|
| 1 | **5 层架构** | 渠道接入 / Agent 编排 / 业务动作层 / 安全治理 / 可观测评估 |
| 2 | **4 级风险分级** | L1 只读 / L2 可逆 / L3 改业务态 / L4 高风险强制人工 |
| 3 | **查询/执行必须分开** | "查询订单"和"修改订单"不能合一个 tool |
| 4 | **不能绕过原业务规则** | "AI Agent 不应该成为绕过原有业务规则的特殊通道" |
| 5 | **幂等是必答题** | "idempotencyKey = 会话ID + 用户确认动作 + 业务对象" |
| 6 | **人工确认不是失败** | "低风险自动处理、高风险带 context 转人工" |
| 7 | **可观测端到端串联** | "traceId 串模型/工具/业务/DB/MQ" |
| 8 | **评估指标要变** | "问题解决率、错误执行率、转接质量、工具成功率、回滚次数" |
| 9 | **小场景起步** | "先做'查物流 + 催发货'，再扩到退款" |
| 10 | **业务上限由后端决定** | "AI 客服上限 = 后端能开放多少业务能力" |

## 2. 现状 vs 差距（按 5 层 + 10 论断展开）

### 2.1 5 层架构覆盖度

| 层 | 当前 | 文章要求 | 状态 |
|---|---|---|---|
| ① 渠道接入 | HttpChannelAdapter | 微信/邮件/电话/企业IM多渠道 | 🟡 HTTP only |
| ② Agent 编排 | AgentLoop + SpringAiAgentAdapter | 维护上下文、动态决定工具 | ✅ |
| ③ 业务动作层 | 7 Tool + @ToolSpec + Port + 多存储 | 4 风险级 + 查询/执行分离 | ✅ |
| ④ 安全治理 | RiskGate + Idempotency + RateLimiter + Audit | 脱敏 + 租户级限流 + traceId | ❌ 3 缺口 |
| ⑤ 可观测 | ToolAuditBridge + AgentMetrics | 端到端串联 + 回放 | 🟡 无 traceId 串联 |

**结论**：第 ③ 层已扎实，第 ④ 层最薄弱，第 ⑤ 层有基础缺串联。

### 2.2 10 论断覆盖度

| # | 论断 | 现状 | 缺口 |
|---|---|---|---|
| 1 | 5 层架构 | 5/5 都有 | 第 ① / ④ 层欠 |
| 2 | 4 级风险分级 | RiskLevel 4 级齐 | — |
| 3 | 查询/执行分离 | OrderTool/RefundTool 已分 | — |
| 4 | 不能绕过原业务规则 | InMemory Store 模拟 | **支付渠道 / 组合优惠 / 退款规则** 三个业务规则未建 |
| 5 | 幂等 | idempotencyKey + Redis 存 | idempotencyKey 缺**生成器**（文章说"会话ID+用户确认动作+业务对象"） |
| 6 | 人工确认 | HumanReviewQueue + L4 admin | HandoffContext 已设计，但**未集成进 SpringAiAgentAdapter 自动触发** |
| 7 | 可观测端到端 | Audit 有，Metrics 有 | **无 traceId 串联每次调用** |
| 8 | 评估指标 | AgentMetrics | 无**问题解决率**指标（依赖会话级追踪） |
| 9 | 小场景起步 | 已有 7 tool | — |
| 10 | 后端能力决定上限 | 7 tool 覆盖 5 业务域 | **新业务规则 Tool**（支付渠道/退款规则/工单升级） |

## 3. 优先级矩阵（按 ROI）

| 任务 | 价值 | 实现成本 | 优先级 | Phase |
|---|---|---|---|---|
| **M1** SensitiveDataMasker | 高（合规） | 低（1 文件） | **P0** | Phase 13a |
| **M2** IdempotencyKeyGenerator | 中（落地文章原话） | 低（1 文件） | P0 | Phase 13a |
| **M3** TenantRateLimiter | 高（防滥用） | 中（1 文件 + 配置） | **P0** | Phase 13a |
| **M4** TraceIdFilter | 高（可观测核心） | 低（1 过滤器） | **P0** | Phase 13a |
| **M5** PayChannelRule + RefundEligibilityRule | 高（业务上限） | 中（2 tool + 测试） | **P0** | Phase 13b |
| **M6** SpringAiAdapter 自动转人工 | 高（文章 §"人工确认"） | 中（改 adapter） | P1 | Phase 13b |
| **M7** 多渠道 Adapter（Feishu/Telegram） | 中（演示用） | 中（2 adapter） | P2 | Phase 14 |
| **M8** E2E LLM 真实对话 | 中（验证） | 高（要 LLM key） | P2 | Phase 15 |

**P0 任务 4 个，预期 1 个 phase 收敛**（~3-4 Task）

## 4. Phase 13a — 治理层补齐（M1+M2+M3+M4）

### M1. SensitiveDataMasker

**文件**：`governance/SensitiveDataMasker.java`

**职责**：在 `ToolAuditBridge` 记录 audit 前脱敏请求/响应中的敏感字段
- 检测字段名（身份证 / 银行卡 / 手机号 / 邮箱 / 收货地址） → 部分 mask
- `138****1234` / `6228 **** **** 1234` / `3301****5678`

**集成点**：
- `ToolInvocationContext.requestJson()` → `MaskedJson.of(original).mask()`
- 不动业务逻辑，只在 audit 出口加

**测试** (~5)：
- 身份证 18 位 → `3301**********1234`
- 银行卡 16-19 位 → `6228**********1234`
- 手机号 11 位 → `138****1234`
- 邮箱 → `t***@example.com`
- 非敏感字段原样返回

### M2. IdempotencyKeyGenerator

**文件**：`governance/IdempotencyKeyGenerator.java`

**职责**：按文章 §"幂等"原话，生成稳定 key
```
key = sha256(sessionId + toolName + businessObjectId + userAction)
```
- 提供 `forCancelOrder(sessionId, orderId)` / `forApplyRefund(sessionId, orderId, reason)` 等语义化方法
- 让 SpringAiAgentAdapter 调用时自动注入

**测试** (~3)：
- 同输入 → 同 key
- 不同 session → 不同 key
- 同会话同动作 → 同 key（重复调用安全）

### M3. TenantRateLimiter

**文件**：`governance/TenantRateLimiter.java`

**职责**：按租户级 QPS 限流（防一个租户霸占全局）
- 每租户独立 Resilience4j RateLimiter（懒加载）
- 默认 50 QPS / 租户，配置可调
- 触发时抛 `TenantRateLimitedException`

**集成点**：
- `DefaultAgentLoop.execute()` 入口
- 或作为 Spring AOP `@TenantRateLimit` 注解

**测试** (~4)：
- 同租户 50 次通过
- 第 51 次抛 TenantRateLimitedException
- 多租户互不影响
- 配置可改

### M4. TraceIdFilter

**文件**：`governance/TraceIdFilter.java` + `governance/TraceContext.java`

**职责**：每次 Agent 会话生成 traceId，串联 audit/metrics
- HTTP Header `X-Agent-Trace-Id` 优先，否则 UUID
- `MDC` 存 `traceId/tenantId/userId/sessionId`
- 集成进 `ToolAuditBridge` 的 `promptBody` 前缀
- 集成进 `AgentMetrics` 的 metric tags

**测试** (~4)：
- Header 存在 → 用 Header
- Header 缺失 → 生成 UUID
- MDC put/clear 不漏
- Audit log 含 traceId（验证 ToolAuditBridge 输出）

### Task 划分

| Task | 内容 | 增量 tests |
|---|---|---|
| Task 1 | M1 Masker + M2 KeyGen | +8 |
| Task 2 | M3 TenantLimiter + M4 TraceId | +8 |
| Task 3 | 集成（RiskGate/Adapter/MDC）+ 全仓库回归 | +2 |
| Task 4 | 文档 + commit + push | 0 |

**Phase 13a 总：+18 tests（148 → 166）**

## 5. Phase 13b — 业务规则 + 转人工（M5+M6）

### M5. PayChannelRule + RefundEligibilityRule

**新工具**：
- `PaymentChannelTool` (L1): `query_payment_channel(orderId) → {channel: WECHAT/ALIPAY/CARD, allowRefund: bool}`
- `RefundRuleTool` (L1): `check_refund_rules(orderId) → {withinWindow: bool, hasComboCoupon: bool, requiresManual: bool, reason: string}`

**改 RefundTool.create_refund**：
- 内部先调 `RefundRuleTool.check_refund_rules`
- 若 `hasComboCoupon=true` → 抛 `ManualReviewRequiredException`（转人工）
- 若 `withinWindow=false` → 抛规则不符

**测试** (~6)：
- 普通订单退款 → 通过
- 组合优惠订单 → 拒绝 + 转人工
- 超出退款期 → 拒绝
- 支付渠道不允许退款（虚拟卡）→ 拒绝

### M6. SpringAiAdapter 自动转人工

**改 `SpringAiAgentAdapter`**：
- 工具执行后若返回 `HandoffSignal`（新 marker interface）或抛 `HandoffRequiredException`
- → 不让模型继续，自动打包 `HandoffContext` → 推 `HandoffService`
- 文章原话："Agent 在转人工之前，已完成用户身份确认、订单信息查询、问题分类、规则匹配和风险说明"

**测试** (~3)：
- 工具抛 HandoffRequiredException → 自动触发 Handoff
- 打包的 Context 含 toolChain + riskNote
- 业务流不继续

### Task 划分

| Task | 内容 | 增量 tests |
|---|---|---|
| Task 1 | M5 PaymentChannelTool + RefundRuleTool | +4 |
| Task 2 | RefundTool 集成 RefundRule | +3 |
| Task 3 | M6 SpringAiAdapter 自动转人工 | +3 |
| Task 4 | 回归 + 文档 + commit + push | 0 |

**Phase 13b 总：+10 tests（166 → 176）**

## 6. 不做（明确边界）

| 不做 | 原因 |
|---|---|
| 多渠道 Adapter（Feishu/Telegram/WeChat） | Phase 14 计划 |
| E2E 真实 LLM 对话 | Phase 15 计划（需 LLM key 投入） |
| 完整 RefundRule 引擎（DSL 表达式） | 业务复杂度高，本阶段硬编码 3 条规则足够演示 |
| 自动回滚机制（cancel/refund 反向） | 跨服务事务，文章也没要求自动回滚；改人工 |
| LLM 选型统一（OpenAI/DeepSeek/Qwen） | 跟 rag-core 走，不在 agent 层做 |

## 7. 测试增量估算

| Phase | 起点 | 增量 | 累计 |
|---|---|---|---|
| 13a | 148 | +18 | 166 |
| 13b | 166 | +10 | 176 |
| 14 多渠道 | 176 | +8 | 184 |
| 15 E2E 真实对话 | 184 | +5 | 189 |

## 8. 风险/边界

1. **M3 TenantRateLimiter 限流键** — 同一租户 50 QPS 是演示值，生产需按租户级别配置
2. **M4 TraceId** — 用 MDC，与现有 Logback 配置联动；不动 rag-core 的 audit 实现
3. **M5 退款规则** — 是 mock 业务规则（不接真实订单系统）；生产需把 PaymentChannelTool 替换为真实支付中台对接
4. **M6 自动转人工** — 仅 SpringAiAgentAdapter 路径，HttpChannelAdapter 路径仍走现有 HandoffService 显式调用

## 9. 落地节奏

- **本回复提交 Phase 13a 计划**（M1-M4 治理层）
- **用户拍板 13a 后执行** → 4 Task 一气呵成
- **13a 完成后** → 自动起 13b 计划 (M5-M6) 提交拍板
- **13b 完成后** → Phase 14 (多渠道) / Phase 15 (E2E) 选一
