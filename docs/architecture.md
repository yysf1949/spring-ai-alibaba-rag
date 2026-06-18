# Architecture — spring-ai-alibaba-rag

> 设计 Spec §6 架构总览 + §13 落地代码映射
>
> 来源文章 §6 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 1. 分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│  Client (Web / App / API Consumer)                              │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP (POST /api/qa, /api/ingest)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  rag-app · Spring Boot 3.3                                      │
│  ├ RagController / IngestController                             │
│  ├ MdcTenantFilter (X-Tenant-Id → MDC)                          │
│  ├ RagExceptionHandler (RFC 7807)                               │
│  └ Resilience4j 429 mapping (RequestNotPermitted)               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  rag-pipeline · 编排层 (orchestration)                          │
│                                                                 │
│  Ingest 链: IngestService → ChunkSplitter → EmbeddingGateway    │
│             → StagingIndex → 灰度 → Publish (atomic switch)     │
│                                                                 │
│  QA 链:    AnswerCache → Rewriter → EmbeddingCache             │
│             → VectorRepository.search (TopK=20) → Rerank (TopN=5)│
│             → ContextAssembler (token budget + PII redact)      │
│             → LlmService → AnswerCache (write-back)            │
└────────┬───────────────────┬──────────────────┬─────────────────┘
         │                   │                  │
         ▼                   ▼                  ▼
┌─────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
│  rag-redis      │ │  rag-embedding   │ │  rag-core (port)     │
│  VectorStore    │ │  EmbeddingGw     │ │  - model/*           │
│  + 3 cache      │ │  + Rerank        │ │  - port/*            │
│  + SessionStore │ │  + LlmService    │ │  - exception/*       │
└────────┬────────┘ └────────┬─────────┘ └──────────────────────┘
         │                   │
         ▼                   ▼
┌─────────────────┐ ┌──────────────────────┐
│  Redis Stack    │ │  SiliconFlow API     │
│  (HNSW COSINE)  │ │  (BAAI/bge-m3)       │
│  + RediSearch   │ │  + Qwen2.5-7B        │
└─────────────────┘ └──────────────────────┘
```

### 1.1 端口隔离 (rag-core)

`rag-core` 是**无 Spring / 无 Redis / 无 LLM** 的纯 Java 领域层,所有外部依赖都是接口:

- `VectorStore` — 向量存储
- `EmbeddingGateway` — Embedding
- `RerankService` — Rerank
- `LlmService` — LLM
- `RewriteService` — Query 改写
- `AnswerCache` / `RewriteCache` / `EmbeddingCache` / `SessionStore`

**好处**:rag-pipeline 编排时只依赖接口,可替换实现 (Stub / SiliconFlow / 未来其他),符合 Hexagonal Architecture (Ports & Adapters)。

### 1.2 异常隔离

5 个 `*UnavailableException` 定义在 `rag-core`:

- `VectorStoreUnavailableException`
- `EmbeddingUnavailableException`
- `LlmUnavailableException`
- `RerankUnavailableException`
- `CacheUnavailableException`

编排层 catch 这些 → 降级 (FALLBACK_RULE);web 层 catch 这些 → 503 + Retry-After。

---

## 2. 摄入链 (Ingest Pipeline, §9-§10)

```
HTTP POST /api/ingest
  → MdcTenantFilter (X-Tenant-Id → MDC)
  → IngestController.submit()
  → IngestServiceImpl.submitJob()
       │ 1. Document.Section[] → Chunk[] (ChunkSplitter, 200-800 token)
       │ 2. 写 staging index (rag:index:{tenant}:{kbVersion}-staging)
       │ 3. 立即返回 jobId (HTTP 202)
       ▼
  → IngestJobExecutor (独立线程池,不与在线查询共享)
       │ 1. EmbeddingGateway.embedBatch() (异步,带缓存)
       │ 2. 写 embedding → Redis HASH
       │ 3. status: PENDING → PROCESSING → READY
       ▼
  HTTP GET /api/ingest/{jobId} → 查询状态

  → POST /api/ingest/{jobId}/publish
       │ 1. 验证 status == READY
       │ 2. atomic 切换 rag:publish:{tenant}:{kbId} → kbVersion
       │ 3. status: READY → PUBLISHED
       │ 4. 旧版本 chunk 异步 DEPRECATED
```

**关键设计**:
- **异步执行** — Controller 不阻塞,返回 jobId
- **独立线程池** — `IngestJobExecutor` 与在线 QA 隔离 (避免 Embedding 流量互殴)
- **灰度发布** — staging → publish 两段式,可叠加灰度验证

---

## 3. 在线链 (QA Pipeline, §11)

```
HTTP POST /api/qa { tenantId, kbId, kbVersion, query, permissionTags[] }
  → MdcTenantFilter (X-Tenant-Id → MDC)
  → RagController.qa()
  → QAServiceImpl.answer()
       │ 1. AnswerCache.get(queryHash)
       │    → hit? return Answer(SOURCE=CACHE) + 写指标
       │ 2. RuleBasedQueryRewriter (同义词 + 客套词去除)
       │    → score < 0.6? 调 LLM rewriter
       │ 3. EmbeddingCache / EmbeddingGateway (Rerank 也走缓存)
       │ 4. VectorStore.search (TopK=20,过滤 tenantId/kbId/kbVersion/status=active/permissionTags)
       │ 5. RerankService.rerank() → TopN=5
       │ 6. ContextAssembler (token 预算 4000, PII redact)
       │ 7. LlmService.generate()
       │ 8. 写 AnswerCache
       │ 9. 记录 Micrometer (stage 耗时 + cache hit + token count)
       ▼
  Answer { finalText, citations[], source, latencyMs, metrics{} }
```

### 3.1 7 层降级 (Cluster 6 + §14)

| 阶段 | 故障 | 降级 |
|---|---|---|
| AnswerCache | Redis 挂 | 跳过缓存,继续走链路 |
| Rewriter | LLM 不可用 | 用规则改写结果 |
| EmbeddingCache | Redis 挂 | 调 SiliconFlow |
| EmbeddingGateway | SiliconFlow 限流 | **CircuitBreaker OPEN** → 抛 `EmbeddingUnavailableException` |
| VectorStore.search | Redis 挂 | **CircuitBreaker OPEN** → 抛 `VectorStoreUnavailableException` → 503 |
| RerankService | 服务挂 | 跳过 Rerank,直接 TopK→TopN=min(TopK,5) |
| LlmService | 失败/超时 | 拼接检索片段,标注 `FALLBACK_RULE` |

### 3.2 Resilience4j 配置 (cluster 6 落地)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redis:        { slidingWindowSize: 10, failureRateThreshold: 50% }
      siliconflow:  { slidingWindowSize: 10, failureRateThreshold: 50% }
  ratelimiter:
    instances:
      qa:           { limitForPeriod: 100, limitRefreshPeriod: 1s }
```

详细配置见 `rag-app/src/main/resources/application.yml`。

---

## 4. Redis Stack 数据布局 (Spec §5, §12)

### 4.1 Key 命名规范

```
rag:chunk:{tenant}:{chunkId}              → Hash (content + metadata + embedding)
rag:index:{tenant}:{kbVersion}            → RediSearch index (HNSW COSINE 1024-dim)
rag:index:{tenant}:{kbVersion}-staging    → 灰度索引 (atomic rename on publish)
rag:answer-cache:{tenant}:{queryHash}     → String (JSON)
rag:rewrite-cache:{tenant}:{queryHash}    → String (JSON)
rag:embedding-cache:{sha256(text)}        → String (JSON float[])
rag:session:{tenant}:{userId}:{sessionId} → Hash (会话摘要)
rag:publish:{tenant}:{kbId}               → String (当前生效 kbVersion)
rag:metrics:{tenant}:{yyyyMMdd}           → Hash (日级指标 HINCRBY)
```

### 4.2 HNSW 参数 (§12.3)

```java
new FTCreateParams()
    .on(IndexDataType.HASH)
    .addPrefix("rag:chunk:" + tenant + ":")
    .addTagField("$.tenantId", "tenantId")
    .addTagField("$.kbId", "kbId")
    .addTagField("$.status", "status")
    .addTagField("$.permissionTags[*]", "permissionTags")
    .addNumericField("$.documentVersion", "documentVersion")
    .addNumericField("$.publishedAt", "publishedAt")
    .addVectorField("$.embedding", VectorFieldAlgorithm.HNSW,
        Map.of(
            "TYPE", "FLOAT32",
            "DIM", "1024",
            "DISTANCE_METRIC", "COSINE",
            "M", "16",
            "EF_CONSTRUCTION", "200",
            "EF_RUNTIME", "10"
        ));
```

### 4.3 搜索过滤顺序 (§15.1)

```java
FT.searchParams()
    .filter("tenantId", "kbId", "kbVersion")
    .filter("status", "active")
    .filter("publishedAt", "0", "now")
    .filter("permissionTags[*]", userPermissionTags)
    .sortBy("__embedding_score", "ASC")  // COSINE distance, 越小越相关
    .limit(0, topK)
    .dialect(2);
```

---

## 5. 可观测性接入点

| 组件 | 指标 / 日志 |
|---|---|
| `IngestServiceImpl` | `rag.ingest.documents.count`, `rag.ingest.duration.ms` + MDC `stage=ingest` |
| `ChunkSplitter` | `rag.ingest.chunks.count` + MDC `stage=split` |
| `QAServiceImpl` | `rag.qa.latency.ms{stage}`, `rag.qa.requests.total{source}` + MDC `stage=qa` |
| `RuleBasedQueryRewriter` | `rag.qa.latency.ms{stage=rewrite}` |
| `EmbeddingGateway` | `rag.embedding.duration.ms{provider}` + CircuitBreaker state |
| `RedisVectorStore.search` | `rag.redis.hnsw.search.ms` + CircuitBreaker state |
| `RerankService` | `rag.qa.latency.ms{stage=rerank}` + `rag.qa.rerank.delta.score` |
| `ContextAssembler` | `rag.qa.context.tokens` + `rag.qa.retrieved.chunks.count` |
| `AnswerCache / EmbeddingCache` | `rag.cache.hit_ratio{type}` |

完整指标列表见 [docs/METRICS.md](./METRICS.md)。

---

## 6. 多租户隔离 (§15)

详细见 [docs/MULTI_TENANT.md](./MULTI_TENANT.md)。核心原则:

1. **tenantId 硬墙** — 任何 query 必须有 tenantId,且写入 / 读取全程强制相等
2. **kbId 白名单** — 用户只能访问已授权的知识库
3. **permissionTags 子集** — 默认 AND 语义 (用户必须具备所有 chunk 的标签)
4. **PII 脱敏** — ContextAssembler 输出前过滤身份证 / 手机号 / 银行卡

---

## 7. 与文章原文的映射

| 文章节 | 章节 | 本文小节 |
|---|---|---|
| §6 | 架构总览 | §1 |
| §10 | 摄入链设计 | §2 |
| §11 | 在线问答链 | §3 |
| §12 | Redis 索引设计 | §4 |
| §13 | 全套代码落地 | §1-§6 (全部) |
| §14 | 高并发 + 降级 | §3.1, §3.2 |
| §15 | 多租户 + 权限 + 脱敏 | §6 + MULTI_TENANT.md |
| §16 | 可观测性 | §5 + METRICS.md |

---

## 8. 阅读顺序建议

1. 先看根 README 架构图 (3 min)
2. 再读 [MULTI_TENANT.md §1](./MULTI_TENANT.md) — 理解硬墙 (5 min)
3. [RUNBOOK.md §2-3](./RUNBOOK.md) — 本地跑起来 (10 min)
4. 本文 §3 在线链 — 理解 7 层降级 (10 min)
5. [LESSONS.md](./LESSONS.md) — 看实际踩过的坑 (15 min)

---

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
