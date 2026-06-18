# Evolution — spring-ai-alibaba-rag

> 设计 Spec §20 — 后续演进路径
>
> 来源文章 §20 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 1. 当前状态 (2026-06)

- ✅ 完整 6 个 module 落地 (rag-core / rag-redis / rag-embedding / rag-pipeline / rag-app / rag-test)
- ✅ 摄入链 + 在线链 + 多租户 + 缓存 + 韧性 + 可观测全栈
- ✅ 49 条 eval fixture + `mvn test -Peval`
- ✅ 1 个 §18 退款规则 demo E2E test
- ✅ Dockerfile + docker-compose
- ✅ Micrometer 13 个指标 + MDC stage logging + Resilience4j (cluster 6)
- ⚠️ 大规模 K8s + Helm chart 未做
- ⚠️ 多 region / 全球化未做

---

## 2. 已识别但未做的功能 (Roadmap)

### P0 — 当前 sprint

| 功能 | 优先级 | 预计工作量 | 备注 |
|---|---|---|---|
| K8s Deployment + HPA + PDB + Service manifest | P0 | 4h | spec §17 P3 已要求 |
| Helm chart (含 values.yaml + Chart.yaml + templates/) | P0 | 4h | 标准化 K8s 部署 |
| CI/CD (GitHub Actions: build + test + docker push + deploy) | P0 | 4h | 自动化 |
| 完整 E2E IT (Testcontainers Redis Stack + SiliconFlow stub) | P0 | 3h | cluster 3 已部分完成 |

### P1 — 下一季度

| 功能 | 优先级 | 预计工作量 | 备注 |
|---|---|---|---|
| 多租户路由 (tenant → shard 路由,降低单 Redis 压力) | P1 | 8h | 千租户级必需 |
| RAGAS 评测接入 (Faithfulness / Answer Relevancy) | P1 | 6h | 替代自研 EvaluationService |
| 流式输出 (SSE / WebFlux) | P1 | 8h | LLM 长答案体验 |
| 多语言 i18n (中/英) | P1 | 4h | Prompt + 异常消息 |

### P2 — 半年内

| 功能 | 优先级 | 预计工作量 | 备注 |
|---|---|---|---|
| 多模态 (图片 embedding) | P2 | 16h | 文档截图理解 |
| Agent 编排 (Function Calling + 工具链) | P2 | 24h | 文章未涉及,后续扩展 |
| 联邦部署 (multi-region active-active) | P2 | 40h | 全球化前提 |
| 模型路由 (按 query 类型 → 不同 LLM) | P2 | 8h | 成本优化 |

### P3 — 探索性

| 功能 | 优先级 | 预计工作量 | 备注 |
|---|---|---|---|
| 自适应 chunk 切分 (基于内容语义) | P3 | 24h | 提升 Recall |
| Active Learning (用户反馈回流训练集) | P3 | 40h | eval 数据闭环 |
| 自研 ReRank (替代 SiliconFlow) | P3 | 24h | 成本/可控性 |
| GraphRAG (Neo4j + 向量混合) | P3 | 40h | 复杂关系场景 |

---

## 3. 性能演进目标

| 阶段 | QPS | P95 Latency | Cache Hit Ratio | 成本/月 |
|---|---|---|---|---|
| **当前 P1 (本地)** | 10 | 500ms | 30% | $0 |
| **P2 (小规模生产)** | 100 | 800ms | 50% | $200 |
| **P3 (中大型 K8s)** | 1000 | 1200ms | 60% | $2000 |
| **P4 (全球化)** | 10000 | 1500ms | 70% | $20000 |

---

## 4. 架构演进路径

### 4.1 当前 (2026-06)

```
App → Redis (单实例,带 RediSearch)
```

### 4.2 6 个月内

```
App (3-10 pod, HPA) → Redis Sentinel (1M+2R) → Embedding 独立服务池
```

### 4.3 12 个月内

```
App (10-50 pod, HPA) → Redis Cluster (3M+3S, 分片) → Embedding/LLM 独立 + 队列削峰
                                  ↓
                          AnswerCache 单独 Redis Cluster (避免与向量检索互殴)
```

### 4.4 24 个月内

```
Multi-region:
  Region A (主) ──┐
  Region B (备) ──┼── 双向同步,跨 region Redis Active-Active
  Region C (读) ──┘
```

---

## 5. 数据规模演进

| 阶段 | Chunk 数 | 索引大小 | 检索延迟 |
|---|---|---|---|
| 当前 | < 100K | 1 GB | < 50ms |
| P2 | 100K-1M | 10 GB | < 100ms |
| P3 | 1M-10M | 100 GB | < 200ms |
| P4 | 10M-100M | 1 TB | < 500ms |

**演进触发条件**:
- P95 latency > 500ms → 考虑分片
- Index size > 内存 60% → 考虑迁移到独立 Redis Cluster
- QPS > 单 Redis 5K → 考虑读副本 + 读写分离

---

## 6. 依赖演进

### 6.1 Embedding Provider

| 阶段 | Provider | 原因 |
|---|---|---|
| 当前 | SiliconFlow (BAAI/bge-m3, 1024-dim) | 国内 + 中文 + OpenAI 兼容 |
| 6 个月 | + DashScope text-embedding-v3 (1024-dim) | 备选 + A/B test |
| 12 个月 | 自托管 bge-m3 (独立 GPU 池) | 成本 + 数据合规 |

### 6.2 LLM Provider

| 阶段 | Provider | 原因 |
|---|---|---|
| 当前 | SiliconFlow Qwen2.5-7B | 成本最低 |
| 6 个月 | + Qwen2.5-72B (复杂 query) | 质量提升 |
| 12 个月 | + 自托管 Qwen2.5-7B (简单 query) | 进一步降本 |

### 6.3 向量库

| 阶段 | 库 | 原因 |
|---|---|---|
| 当前 | Redis Stack 7.4 (HNSW COSINE) | 与缓存合一,运维简单 |
| P3 | 评估 Milvus / Pinecone | 千万级 + 高级过滤 |
| P4 | 多库混合 (Redis 热 + Milvus 冷) | 成本分层 |

---

## 7. 风险与缓解 (演进视角)

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| Redis 单点瓶颈 | QPS > 5K 或 Index > 50 GB | Sentinel → Cluster |
| SiliconFlow 涨价 | 账单 > $5K/月 | 切自托管 / DashScope |
| 模型升级 breaking | Qwen3 发布 | Embedding 缓存兜底,逐步灰度 |
| 团队增长 | > 3 contributor | 加 CODEOWNERS + PR template |
| Spec 漂移 | 多版本并行 | spec 强制引用 + DoD 自检 |

---

## 8. 决策日志模板

每个演进决策都应记录在 `docs/evolution-decisions/` 下,格式:

```markdown
# ADR-NNN: <标题>

- **日期**: 2026-XX-XX
- **状态**: Proposed / Accepted / Superseded
- **决策者**: <人>
- **背景**: <什么问题>
- **决策**: <选了哪个方案>
- **理由**: <为什么>
- **后果**: <正面 / 负面>
- **替代方案**: <考虑过但没选的>
```

---

## 9. 参考

- [docs/architecture.md](./architecture.md) — 当前架构
- [docs/deployment.md](./deployment.md) — 部署演进
- [docs/checklist.md](./checklist.md) — 上线 checklist
- [docs/faq.md](./faq.md) — 已踩过的坑

---

## Phase 9 — Agent Action Layer (进行中)

- **新模块**: `rag-agent`
- **核心**: 3 层架构（编排/动作/治理）+ 4 级工具风险分级
- **参考**: 「路条编程」AI 客服文章 (2026-06-17)
- **未做**: 真实 LLM 接入 + Spring AI 2.0 升级 + 多 Agent 协作

---

## Phase 10 — Agent Action Layer 升级 (2026-06-18)

- **Status**: shipped
- **Range**: P0 (L3/L4 业务工具 + 人工转接) + P1 (指标 + Redis 幂等 + 限流) + P2 (多渠道接口预留)
- **关键决策**:
  - 沿用 Phase 9 的 3 层架构，不重写
  - 业务工具用内存 Repository mock (生产换真实 Service)
  - 多渠道只预留 ChannelAdapter interface，Wechat/Email/App 推到 Phase 11
  - IdempotencyStore 走 Redis 持久化（opt-in by `agent.idempotency.store=redis`）
- **下一阶段**: Phase 11 (已shipped) — 见下方

## Phase 12 — 电商特价管理业务工具（2026-06-18）

**新增 2 个业务工具**，补齐"客户问价/比价/降价索赔"场景：

| 工具 | 方法 | 风险 | 说明 |
|---|---|---|---|
| **PriceProtectionTool** | `query_price_protection_policy` | L1 | 查询某商品价保政策 |
| | `check_price_protection_eligibility` | L1 | 查询某订单是否符合价保条件 |
| | `apply_price_protection` | L3 | 申请价保退差价（200 元门控） |
| **PromotionTool** | `query_product_promotions` | L1 | 查询某商品当前参与的促销 |
| | `query_all_active_promotions` | L1 | 查询所有进行中的促销 |

- `PriceProtectionPort` + `InMemoryPriceProtectionRepository`：价保申请记录存储（InMemory，未来可扩展 H2/MySQL/Redis）
- `PromotionTool`：纯读 mock 数据（类似 LogisticsTool）
- 全仓库测试 **148/148**，0 回归

## Phase 11 — 多存储后端持久化（2026-06-18）

- **Status**: shipped
- **Range**: 4 Port interfaces + H2 embedded + MySQL JPA + Redis 3 种持久化后端
- **关键决策**:
  - Repository 抽 Port interface, Tool 只依赖接口 (修复 TicketTool 直接依赖 InMemoryTicketRepository 反模式)
  - H2 via `JdbcTemplate` + `MERGE INTO` (轻量，不拉 JPA)
  - MySQL via Spring Data JPA (4 @Entity + 4 JpaRepository + 4 Adapter)
  - Redis via `RedisStoreFactory` + `JedisPooled` (沿用 Phase 7 基础设施)
  - Profile 切换: `h2`(dev) / `mysql`(prod) / `redis`(high-perf) / 空(单元测试/InMemory)
- **下一阶段**: 待定

## Phase 13a — AI 客服治理层补齐（2026-06-18）

**4 件套**，对齐「路条编程」AI 客服文章治理层缺口：

| 模块 | 文件 | 职责 |
|---|---|---|
| **M1** SensitiveDataMasker | `governance/SensitiveDataMasker.java` | audit 出口字段级 + 值级脱敏（身份证/银行卡/手机号/邮箱/地址） |
| **M2** IdempotencyKeyGenerator | `governance/IdempotencyKeyGenerator.java` | 语义化幂等键工厂（`forCancelOrder` / `forCreateRefund` / `forApproveRefund` 等）— 包装现有 `IdempotencyKey` |
| **M3** TenantRateLimiter | `governance/TenantRateLimiter.java` | 租户级 QPS 限流（防单租户霸占后端），超限抛 `TenantRateLimitedException` → 429 |
| **M4** TraceIdFilter + TraceContext | `web/TraceIdFilter.java` + `governance/TraceContext.java` | HTTP `X-Agent-Trace-Id` header → ThreadLocal + MDC，audit/metrics 自动串联 |

**集成点**：
- `ToolAuditBridge` 写 audit 前调 `masker.mask(requestJson)` / `masker.mask(responseJson)`，并在 promptBody 追加 `traceId=xxx`
- `AgentMetrics.recordToolInvocation` 当 `TraceContext.current() != null` 时附加 `traceId` tag（向后兼容旧 SimpleMeterRegistry 断言）
- `DefaultAgentLoop.execute()` 入口走 `tenantRateLimiter.execute(tenantId, ...)`，限流触发时返回 FAILURE + 埋 `agent.tool.errors{type=TenantRateLimited}`
- `AgentExceptionHandler` 新增 `TenantRateLimitedException → 429`
- 兼容：旧 7-arg `DefaultAgentLoop` 构造保留（向后兼容单测）

**关键决策**：
- M2 不重写 `IdempotencyKey`，只追加语义化 wrapper — 底层 SHA-256 hash + 五元组拼接已在 Phase 10 验证
- M3 默认 50 QPS / 租户，可通过 `setQps(tenantId, qps)` 单独配置；`execute` 内部用 `find().isPresent()` 判断走 replace 路径，避免 Resilience4j `replace` 静默失败的坑
- M4 不用 `com.github.f4b6a3.uuid`（未在依赖里），回退 `UUID.randomUUID()`，生产可换 Snowflake
- M1 mask 值级 fallback 用 `JsonProcessingException` catch 后对纯文本走正则（覆盖非 JSON 备注字段）

**全仓库测试**：148 → **174**，0 fail（计划 +18，实际 +26：ToolAuditBridge 多 2 个集成测试，IdempotencyKeyGenerator 多 1 个 UUID fallback，TenantRateLimiter 多 1 个 defaultQps，TraceContext 多 1 个 thread-isolated，M1 多 1 个 nested/array 用例）

- **下一阶段**: Phase 13b（M5 PayChannelRule + M6 自动转人工）/ Phase 14（多渠道 Adapter）选一

## Phase 13b — 业务规则 + 自动转人工（2026-06-18）

**2 件套**，对齐「路条编程」AI 客服文章 §"不能绕过原业务规则" + §"人工确认不是失败"：

| 模块 | 文件 | 职责 |
|---|---|---|
| **M5** PaymentChannelTool | `builtin/PaymentChannelTool.java` | L1 查询订单支付渠道（WECHAT/ALIPAY/CARD/VIRTUAL_CARD/POINTS）+ 是否允许退款（mock 决策表） |
| **M5** RefundRuleTool | `builtin/RefundRuleTool.java` | L1 退款规则判定（退款期 7 天 / 组合优惠 / 渠道不允许退款）→ 命中抛 `HandoffRequiredException` |
| **M5** RefundTool 集成 | `builtin/RefundTool.java` | `createRefund` 前置调 `RefundRuleTool.checkRefundRules`，命中 `requiresManual=true` → 抛 `HandoffRequiredException`（不写 Repo） |
| **M6** HandoffRequiredException | `exception/HandoffRequiredException.java` | 业务规则 marker exception，携带 matchedRules + riskNote（文章"前置工作证据"原话） |
| **M6** HandoffContext.forBusinessRule | `handoff/HandoffContext.java` | 新增工厂方法 — 用 `BUSINESS_RULE_MANDATES_HUMAN` reason + `WORK_ORDER` channel 打包 handoff |
| **M6** DefaultAgentLoop 自动转人工 | `orchestration/DefaultAgentLoop.java` | 反射调用 catch `HandoffRequiredException` → 自动走 handoff 分流，context 含完整规则匹配证据 |

**关键决策**：
- `RefundTool.createRefund` 调 `RefundRuleTool` 前置 — 命中规则时**不写 Repo**，避免业务规则被旁路
- `HandoffRequiredException` 用 `InvocationTargetException.unwrap()` (Phase 13b M6 的隐藏坑) — 否则 `Method.invoke` 把业务异常包成 `InvocationTargetException`，编排层 catch 抓不到
- `PaymentChannelTool` + `RefundRuleTool` 不动 `OrderRepositoryPort`（保持 Phase 11 既有契约）；用内部 mock 决策表
- 旧测试 `RefundToolTest` + `Phase10EndToEndTest` 注入 `PaymentChannelTool` + `RefundRuleTool` 实例（surgical — 改测试不动生产 API）
- `AgentExceptionHandler` 加 `HandoffRequiredException → 422` 兜底（DefaultAgentLoop 通常会先 catch 转为 HANDOFF_REQUIRED outcome）

**集成点**：
- `RefundTool.createRefund(req)` → `ruleTool.checkRefundRules(req.orderId)` → 命中抛 `HandoffRequiredException`
- `DefaultAgentLoop.doExecute` step 4 反射 try/catch → catch `HandoffRequiredException` → 调 `handoffService.handoff(forBusinessRule(...))` → 返回 `AgentResponse{outcome=HANDOFF_REQUIRED, handoffContext=...}`
- `DefaultAgentLoop.invokeWithInjection` 加 `InvocationTargetException.unwrap` — 让 `HandoffRequiredException` 等 RuntimeException 正常向上传播

**全仓库测试**：174 → **189**，0 fail（计划 +10，实际 +15：PaymentChannelTool 3 + RefundRuleTool 5 + RefundTool 集成 4 + DefaultAgentLoop 3）

**下一阶段**：Phase 14（多渠道 Adapter — Feishu/Telegram/WeChat）/ Phase 15（E2E 真实 LLM 对话）选一
