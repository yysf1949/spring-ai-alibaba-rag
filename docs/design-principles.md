# Design Principles — spring-ai-alibaba-rag

> 设计 Spec §7 — 12 条架构原则 + 落地解释
>
> 来源文章 §7 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 12 条原则总览

| # | 原则 | 落地位置 | 教训章节 |
|---|---|---|---|
| 1 | **端口隔离** — 外部依赖都是接口,实现可替换 | `rag-core/port/*` | LESSONS §9 |
| 2 | **领域模型无 Spring / 无 LLM / 无 Redis** | `rag-core/model/*` (POJO record) | — |
| 3 | **异步执行,同步接口** — Controller 不阻塞 | `IngestController` 202 + `IngestJobExecutor` | LESSONS §10 |
| 4 | **降级优于失败** — 7 层降级链路 | `QAServiceImpl` + cluster 6 | LESSONS §14 |
| 5 | **缓存前置** — Answer / Embedding / Rewrite 3 层 | `rag-redis/cache/*` + `QAServiceImpl` | LESSONS §1, §9 |
| 6 | **租户硬墙** — tenantId 永不跨 | `MdcTenantFilter` + `RedisVectorStore.search()` 过滤 | MULTI_TENANT §1 |
| 7 | **可观测先行** — Micrometer + MDC 一起接 | `MetricsConfig` + `PipelineMdc` | LESSONS §13 |
| 8 | **配置外置** — application.yml + 环境变量分层 | `rag-app/src/main/resources/application.yml` | — |
| 9 | **测试金字塔** — 单测 > 集成 > E2E | 29 个测试文件,180+ 用例 | LESSONS §13 |
| 10 | **文档即代码** — LESSONS + RUNBOOK + spec 三件套 | `docs/` 8 个 md | — |
| 11 | **Spec 优先** — 实现前先写 spec,实现后回归 spec | `docs/superpowers/specs/*` + DoD §16 验收 | LESSONS §11 |
| 12 | **Surgical 变更** — 不顺手重构,改完跑测 | commit message + diff stat | LESSONS §9 |

---

## 1. 端口隔离 (Ports & Adapters)

**原则**: 外部依赖 (Redis / SiliconFlow / LLM) 都是接口,实现可替换,编排只依赖接口。

**落地**:
```java
// rag-core/port/VectorStore.java
public interface VectorStore {
    List<SearchResult> search(Query query, int topK);
    void upsert(List<Chunk> chunks);
    void publish(String tenantId, String kbId, long version);
}

// rag-redis/vector/RedisVectorStore.java  -- 实现
// rag-pipeline/test/StubVectorStore.java    -- 测试 stub
```

**好处**:
- 单测可全部 mock,无 Redis 依赖
- 未来切 Pinecone / Milvus 只改 adapter
- SiliconFlow 限流/挂掉时切 stub 走 fallback

**教训** (LESSONS §9): 早期设计若把 `RedisVectorStore` 直接注入到 `QAServiceImpl`,后续单测会强依赖 Testcontainers 或 localhost Redis,大幅降低测试速度。

---

## 2. 领域模型纯净

**原则**: `rag-core/model/*` 是 Java record,无 Spring 注解 / 无 Redis 注解 / 无 LLM 注解。

**反例** (踩过的坑):
```java
// ❌ 把 @RedisHash("chunk") 打在 domain model 上
@RedisHash("chunk")
public class Chunk { ... }

// ✅ 通过 codec 层 (RedisVectorStoreCodec) 转换
public record Chunk(String chunkId, String content, float[] embedding, ...) {}
```

**好处**:
- 领域模型可跨 module 共享,无传递依赖
- 单测无需 Spring 上下文
- 序列化为 JSON 时干净 (无 framework annotation noise)

---

## 3. 异步执行,同步接口

**原则**: HTTP 摄入链立刻返回 jobId,处理在后台线程池。

**落地**:
```java
// rag-app/web/IngestController.java
@PostMapping
public ResponseEntity<IngestJob> submit(@Valid @RequestBody IngestRequest req) {
    String jobId = ingestService.submitJob(req.toDocument());
    return ResponseEntity.accepted().body(ingestService.getJob(jobId));  // 202
}

// rag-pipeline/ingest/IngestJobExecutor.java  -- 独立 ScheduledExecutorService
```

**关键**:
- 独立线程池 (`rag.ingest.executor.core-size=2`),**不与在线查询共享**
- jobId 是 UUID,客户端轮询 `GET /api/ingest/{jobId}`
- 状态机: `PENDING → PROCESSING → READY → PUBLISHED` 或 `FAILED`

**为什么**:
- Embedding 一次 100 chunks × 1024 维 ≈ 5s,同步会阻塞 HTTP 线程
- 摄入流量突刺不能挤压在线查询
- 失败可重试,不丢用户数据

---

## 4. 降级优于失败

**原则**: 任何外部依赖失败,系统不整体挂掉,而是降级返回部分结果。

**7 层降级** (见 architecture.md §3.1):

| 层级 | 故障 | 降级动作 |
|---|---|---|
| AnswerCache | Redis 挂 | 跳过缓存,继续走 |
| Rewriter | LLM 限流 | 用规则改写 |
| EmbeddingCache | Redis 挂 | 调 SiliconFlow |
| EmbeddingGateway | SiliconFlow 限流/挂 | CircuitBreaker OPEN → 抛 `EmbeddingUnavailableException` → QA 链 fallback |
| VectorStore | Redis 挂 | CircuitBreaker OPEN → 抛 `VectorStoreUnavailableException` → 503 |
| RerankService | 挂 | 跳过 Rerank,TopK→TopN |
| LlmService | 失败 | 拼接检索片段 + FALLBACK_RULE 标注 |

**教训** (LESSONS §14): 不要对每个失败做 try-catch,要在 Resilience4j 配置里声明 `recordExceptions`,让框架决定何时 fallback。

---

## 5. 缓存前置

**原则**: 3 层缓存 — Answer (最终答案) / Embedding (向量) / Rewrite (改写结果)。

**Key 设计**:
```
rag:answer-cache:{tenant}:{queryHash}      -- 命中直接返回,latency < 5ms
rag:embedding-cache:{sha256(text)}         -- 跨租户共享,降 embedding 成本 70%+
rag:rewrite-cache:{tenant}:{queryHash}     -- 规则改写结果也可缓存
```

**失效策略**:
- AnswerCache TTL = 1h (配置可调)
- EmbeddingCache 无 TTL,定期清理孤儿
- 知识库发布时主动清 AnswerCache (基于 tenantId 模糊清)

**教训** (LESSONS §1): 早期没监控 hit ratio,部署一周后才发现 QPS 没降但 SiliconFlow 调用量翻倍,缓存根本没生效。**监控必须先于部署**。

---

## 6. 租户硬墙

**原则**: `tenantId` 绝不跨租户,任何 query 必须 tenantId 强制相等。

**实现**:
```java
// rag-redis/vector/RedisVectorStore.java  -- 搜索时过滤
FTSearchParams params = FTSearchParams.searchParams()
    .filter("tenantId", query.tenantId())   // 硬墙
    .filter("kbId", query.kbId())
    .filter("kbVersion", String.valueOf(query.kbVersion()))
    .filter("status", "active")
    .filter("publishedAt", "0", String.valueOf(System.currentTimeMillis()));
```

详细见 [MULTI_TENANT.md](./MULTI_TENANT.md)。

**为什么**:
- 即使 SQL 注入 / 越权访问,Redis 搜索层也会过滤
- 测试可验证 (用 tenant A 的 chunkId,tenant B 查不到)

---

## 7. 可观测先行

**原则**: Micrometer + MDC + Prometheus **在第一个 commit 就要接入**,不能事后补。

**指标三层**:
- **业务指标** — `rag.qa.requests.total{source=CACHE/LLM/FALLBACK_RULE}`
- **性能指标** — `rag.qa.latency.ms{stage=rewrite/embed/retrieve/rerank/generate}`
- **资源指标** — `rag.cache.hit_ratio`, `rag.embedding.daily_tokens`

**MDC 字段** (C5 cluster 5):
```
tenantId, userId, sessionId, queryHash, requestId,
stage, retrieved, reranked, tokens, latencyMs, source
```

**为什么**:
- 没有指标的 RAG 系统是黑盒,出问题只能猜
- P95 延迟突刺时,需要看到是哪个 stage 慢
- 业务方问"今天 QPS 多少",直接 `rag.qa.requests.total` 拿

**教训** (LESSONS §13): cluster 5 第一次接入 MDC 时漏掉 `requestId`,导致并发请求日志混在一起。MDC 字段必须 **单一职责 + 全链路一致**。

---

## 8. 配置外置

**原则**: 所有可变配置走 `application.yml` + 环境变量,代码里不出现 magic number。

**分层**:
```yaml
# application.yml (default)
spring:
  rag:
    ingest:
      executor:
        core-size: 2
        max-size: 8
    qa:
      cache:
        ttl-seconds: 3600

# application-docker.yml (override)
spring:
  rag:
    redis:
      host: redis          # service name in compose
      port: 6379

# 环境变量 (CI/CD)
SPRING_APPLICATION_JSON='{"spring":{"rag":{"redis":{"host":"prod-redis-1"}}}}'
```

**好处**:
- 同 jar 包,不同 profile 跑不同环境
- CI 不需要改代码就能切换配置
- K8s ConfigMap/Secret 解耦

---

## 9. 测试金字塔

**原则**: 单测 > 集成测试 > E2E,数量按 70 / 20 / 10 分布。

| 层 | 工具 | 数量 | 速度 |
|---|---|---|---|
| 单测 | JUnit 5 + Mockito | 180+ | < 1s/个 |
| 集成测试 | Testcontainers Redis | 5 (可选) | 30s/个 |
| E2E | `@SpringBootTest` + `-DrunIT=true` | 2 | 60s/个 |

**教训** (LESSONS §13): E2E 必须有显式开关 (`-DrunIT=true`),否则 CI 每次都跑会拖慢 PR review。

---

## 10. 文档即代码

**原则**: 文档不是事后补,是 commit 的一部分。

**结构**:
```
docs/
├── README.md                 # 文档总览
├── RUNBOOK.md                # 运维 (本地 + Docker + smoke test)
├── LESSONS.md                # 14 节实战教训
├── METRICS.md                # Prometheus 指标全集
├── MULTI_TENANT.md           # 多租户契约
├── architecture.md           # §6 架构 (本文档)
├── design-principles.md      # §7 12 条原则 (本文档)
├── observability.md          # §16 指标体系 + 日志 + 评估
├── deployment.md             # §17 部署演进
├── evolution.md              # §20 演进路径
├── checklist.md              # §21 生产落地 checklist
├── faq.md                    # §19 高频坑
├── plans/                    # 设计 + 实施 plan
├── superpowers/              # spec + 工作流
└── eval/                     # eval fixture + 报告
```

**教训**: LESSONS.md 是 dev diary,任何踩过的坑都要写下来,不只是给自己看,也是给后续 contributor。

---

## 11. Spec 优先

**原则**: 实施前先写 spec,实施后回归 spec 的 DoD。

**流程**:
1. 写 `docs/superpowers/specs/<date>-<project>-design.md` — 包含目标 / 技术栈 / 模块结构 / 数据模型 / API / 测试 / DoD
2. 用 writing-plans skill 拆 phase / cluster / task
3. 每个 task 跑完跑回 spec 对照 (DoD 8 条 checklist)
4. commit message 引用 spec 章节号 (`closes spec §X.Y`)

**教训** (LESSONS §11): 早期 P1-P5 没强绑 spec,导致后来 cluster 6 出现 spec 缺口时,要花一周回填 spec。

---

## 12. Surgical 变更

**原则**: 不顺手重构,改完跑测,改完 commit。

**操作**:
- 不删看似无关的代码 (即使是 dead code)
- 不改注释格式 / 不重命名非任务变量
- 改动范围 < 200 行 (cluster 级) / < 50 行 (task 级)
- 每个 commit = 1 个独立逻辑单元

**教训** (LESSONS §9): 一次重构改了 12 个文件,结果 3 个 module 单测同时挂,回滚花了一小时。**单 commit 单测必须绿**,不绿不 commit。

---

## 13. (隐式第 13 条) 真实验证优先

**原则**: 完成 = 跑通 + 远端 push + CI 绿 + DoD 全过,不是"我看了下好像 OK"。

**验证 ritual**:
1. `mvn -pl <changed-module> test` — 单 module 绿
2. `mvn verify` — 全 module 绿
3. `git push` — 远端 HEAD = 本地 HEAD
4. `git ls-remote origin main` — 远端 HEAD 没掉队

**教训**: 多次出现"我以为 push 了"但实际 push 失败,导致下一 cluster 接力时基于旧 HEAD 操作。

---

## 14. 工具风险分级 (Phase 9 新增)

**原则**: 每个 Agent 可调用的工具必须明确标注风险级别（L1-L4），治理层强制门控。

| 级别 | 治理要求 |
|---|---|
| L1 | 自动放行 |
| L2 | idempotencyKey 强制 |
| L3 | idempotencyKey 强制 + 用户二次确认 |
| L4 | idempotencyKey 强制 + admin 角色 |

**反模式**: 把"查询"和"修改"做成同一个大工具 — 模型选错参数就把查询变写操作。

参考「路条编程」AI 客服文章 §"查询 ≠ 执行，必须拆开"。

---

## 15. 工具调用幂等性

- **强制**: L2+ 工具必须接收 `idempotencyKey` 参数（RiskGate 校验）
- **存储**: 默认 InMemory（重启丢），生产建议 Redis（Phase 10 Task 10）
- **TTL**: 30s 占位，replace 不延寿
- **测试**: 每个写工具必须有 "重复调用同 token → 同结果" 单测

## 16. 评估指标以端到端为中心

- 不只看"回答准确率"，要测"工具被调用的成功率/平均调用次数/失败原因/回滚次数/用户确认率"
- 5 个核心指标走 Micrometer → Prometheus（详见 observability.md §11）
- "端到端问题解决率" 需业务反馈信号，不在 Agent Metrics 范围
