# Spring AI Alibaba + Redis 企业级 RAG 引擎 — 设计 Spec

- **日期**: 2026-06-16
- **来源文章**: 微信公众号「Ray 的银河技术」《Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战》(mp.weixin.qq.com/s/FF-A3nUhnnV0HhTFfVmS-A)
- **作者**: 周礼攀 (yysf1949)
- **状态**: Draft — 待 user 审阅

---

## 1. 目标

把上述 42K 字 / 22 节文章**完整落地**为一个可编译、可运行、可部署的 Spring Boot 3.x 工程，私有仓库 `yysf1949/spring-ai-alibaba-rag`。覆盖文章全部 22 节：

- **能落代码的**（§6 架构 + §9-§13 摄入链 + 在线链 + Redis 设计 + §13 全套代码 + §15 多租户 + §18 真实案例）：**全部实现**
- **纯架构原则 / 工程实践 / 部署演进**（§7 12 条原则 + §14 高并发 + §16 可观测性 + §17 部署 + §20 演进 + §21 checklist）：**落到 README + docs/ + 代码注释 + 单元测试**
- **场景化案例**（§18 退款规则问答）：**写一个端到端 demo test**

## 2. 技术栈与版本

| 维度 | 选型 | 版本 | 理由 |
|---|---|---|---|
| JDK | OpenJDK | 21（已装） | Spring Boot 3.x 基线 |
| 构建 | Maven | 3.9.x（自装） | user 决策 |
| 框架 | Spring Boot | 3.3.x | 与 Spring AI 1.0.x 兼容 |
| AI 框架 | Spring AI Alibaba | 1.0.x-M6+ | 含 DashScope Starter / Vector Store |
| Embedding | DashScope text-embedding-v3 | 1536 维 | 文章 §13.1 默认 |
| LLM | DashScope qwen-plus / qwen-max | — | 文章 §13.11 |
| 向量库 | Redis Stack | 7.4+ | 含 `redisearch` 模块，支持 HNSW |
| 缓存 | Redis（同上） | — | answer-cache / rewrite-cache / embedding-cache |
| 测试 | JUnit 5 + Mockito + Testcontainers | — | Redis Stack 用 Testcontainers |
| 可观测 | Micrometer + Prometheus + Lombok@Slf4j | — | 文章 §16 |
| 部署 | Dockerfile + docker-compose | — | 文章 §17 |

## 3. 模块结构（多 module Maven）

```
spring-ai-alibaba-rag/
├── pom.xml                          # parent
├── rag-core/                        # 领域模型 + 接口
│   ├── model/                       # Chunk, Query, Answer, Tenant, KnowledgeBase
│   ├── port/                        # VectorStore, EmbeddingGateway, RerankService, RewriteService
│   └── exception/
├── rag-redis/                       # Redis 实现（向量 + 缓存 + 会话）
│   ├── vector/                      # RedisVectorStore, RedisIndexManager, VectorRepository
│   ├── cache/                       # AnswerCache, EmbeddingCache, RewriteCache
│   └── session/                     # SessionStore
├── rag-embedding/                   # Embedding 网关
│   └── DashScopeEmbeddingGateway    # 含批量、超时、降级
├── rag-pipeline/                    # 摄入链 + 在线链编排
│   ├── ingest/                      # IngestService, ChunkSplitter, StagingIndexManager
│   ├── rewrite/                     # RuleBasedQueryRewriter + LLM fallback
│   ├── rerank/                      # RerankService 接口 + DashScope 实现
│   ├── context/                     # ContextAssembler (Prompt 预算)
│   └── qa/                          # QAService (缓存 → 召回 → 重排 → 生成 → 降级)
├── rag-app/                         # Spring Boot 应用入口
│   ├── controller/                  # POST /ingest, POST /qa
│   ├── config/                      # Redis, DashScope, Metrics
│   └── application.yml
├── rag-test/                        # 集成测试 + 真实案例 demo
│   └── ...                          # Testcontainers Redis + Mock DashScope
├── docs/                            # 设计/计划/部署文档
│   ├── superpowers/
│   │   ├── specs/
│   │   └── plans/
│   ├── architecture.md              # §6 架构总览
│   ├── design-principles.md         # §7 12 条原则 + 解释
│   ├── observability.md             # §16 指标体系 + 日志 + 评估
│   ├── deployment.md                # §17 部署演进
│   ├── evolution.md                 # §20 演进路径
│   ├── checklist.md                 # §21 生产落地 checklist
│   └── faq.md                       # §19 高频坑
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml           # Redis Stack + App
├── scripts/
│   └── demo-refund-qa.sh            # §18 退款规则 demo
└── README.md                        # 完整使用说明 + 架构图
```

## 4. 核心数据模型（§13.4）

```java
record Chunk(
    String chunkId,            // uuid
    String tenantId,
    String kbId,
    String documentId,
    String documentVersion,
    String title,
    String sectionPath,
    String content,
    Set<String> permissionTags,
    ChunkStatus status,        // STAGING | ACTIVE | DEPRECATED
    Instant publishedAt,
    String sourceUri,
    float[] embedding          // 1536 dim
) {}

record Query(
    String tenantId,
    String userId,
    String sessionId,
    String rawText,
    Set<String> permissionTags,  // 用户权限标签
    int topK,
    KbVersion kbVersion         // 指定检索哪个版本
) {}

record Answer(
    String tenantId,
    String queryHash,
    String rewrittenQuery,
    List<Chunk> retrieved,
    List<Chunk> reranked,
    String finalText,
    List<Citation> citations,
    AnswerSource source,        // CACHE | LLM | FALLBACK_RULE
    long latencyMs,
    Map<String, Object> metrics
) {}
```

## 5. Redis 索引设计（§12 — 直接照搬文章）

### 5.1 Key 命名规范

```
rag:chunk:{tenant}:{chunkId}              → Hash, content + metadata
rag:index:{tenant}:{kbVersion}            → RediSearch 索引名 (HNSW)
rag:answer-cache:{tenant}:{queryHash}     → 最终答案
rag:rewrite-cache:{tenant}:{queryHash}    → 改写结果
rag:embedding-cache:{sha256(text)}        → embedding 向量
rag:session:{tenant}:{userId}:{sessionId} → 会话摘要
rag:publish:{tenant}:{kbId}               → 当前生效 kbVersion
rag:metrics:{tenant}:{yyyyMMdd}           → 日级指标 (HINCRBY)
```

### 5.2 Chunk 元数据字段（§12.2 表 → 索引 schema）

| 字段 | Redis 类型 | 用途 |
|---|---|---|
| `chunkId` | TAG | 主键 |
| `tenantId` | TAG | 多租户过滤 |
| `kbId` | TAG | 知识库 |
| `documentId` | TAG | 文档定位 |
| `documentVersion` | NUMERIC | 版本控制 |
| `status` | TAG | staging/active |
| `publishedAt` | NUMERIC | 新鲜度排序 |
| `permissionTags` | TAG[] | 权限过滤（多值） |
| `title` | TEXT | 召回辅助 |
| `content` | TEXT | 展示 |
| `embedding` | VECTOR (HNSW, 1536, COSINE) | 向量召回 |

### 5.3 HNSW 参数（§12.3）

- 10 万 chunk 以内：可选 FLAT
- 10 万 - 数百万：**HNSW** (默认)
  - `M = 16`
  - `EF_CONSTRUCTION = 200`
  - `EF_RUNTIME = 10`
  - 距离度量：`COSINE`
- 通过 Testcontainers 压测在 `rag-test` 给出 demo 数

## 6. 摄入链（§10）

### 6.1 时序

```
HTTP 上传 → 解析 (PDF/Word/MD) → ChunkSplitter
  → EmbeddingGateway.embedBatch (异步 + 缓存)
  → 写入 staging index (rag:index:{tenant}:{kbVersion}-staging)
  → 灰度验证（Recall@K / 引用覆盖率 / 抽样人工）
  → 原子切换 rag:publish:{tenant}:{kbId} → kbVersion
  → 旧版本 chunk 异步标记 DEPRECATED，7 天后清理
```

### 6.2 ChunkSplitter（§10.4）

- 优先按"语义段落"切（句末 + 段落头）
- 单 chunk 200-800 token，滑动窗口 overlap 50 token
- 大表格/代码块整体保留不切
- 元数据继承：tenantId/kbId/documentId/version/title/sectionPath/permissionTags

### 6.3 异步化（§10.1）

- Controller 立即返回 `ingestJobId` (202)
- `IngestJobExecutor` 后台线程池（独立 pool，**不与在线查询共享**）
- 状态：`PENDING / PROCESSING / READY / PUBLISHED / FAILED`
- 进度查询：`GET /ingest/{jobId}`

## 7. 在线问答链（§11）

### 7.1 标准链路

```
Query → AnswerCache (hit?)
  → [hit] return Answer(SOURCE=CACHE)
  → [miss] RewriteService (rule + LLM)
       → EmbeddingCache / EmbeddingGateway
       → VectorRepository.search (过滤: tenantId, kbId, kbVersion, status=active, permissionTags ⊆ user)
       → RerankService (TopK → TopN=5)
       → ContextAssembler (token 预算控制, 默认 4000 token)
       → LLM.generate (Prompt 模板 + 来源引用指令)
       → 写入 AnswerCache
       → return Answer
```

### 7.2 Query Rewrite（§11.2）

- **规则优先**：同义词表 + 关键词补全 + 去除客套词
- LLM 兜底：仅当规则 score < 0.6 才调用
- 结果写入 `rag:rewrite-cache:{tenant}:{queryHash}`

### 7.3 Rerank（§11.3）

- 接口：`RerankService.rerank(query, candidates, topN)`
- 默认实现：DASHSCOPE `gte-rerank`
- 通过 SPI 可替换

### 7.4 ContextAssembler（§13.10）

- token 预算：默认 4000
- 按 rerank 分数排序 + 截断
- 必须保留 `title + sectionPath + sourceUri` 用于引用展示
- 超预算时压缩 content，**永不截断元数据**

### 7.5 降级（§14.3）

- LLM 失败/超时 → 返回"基于检索片段的直接拼接回答"，标注 `SOURCE=FALLBACK_RULE`
- 重排失败 → 直接用向量 TopK
- 召回为空 → 返回兜底话术 + 列出最近 N 条热门问题

## 8. 多租户与权限（§15）

### 8.1 过滤顺序（§15.1）

```
1. tenantId 强制相等（硬墙，绝不跨租户）
2. kbId 白名单（用户授权知识库）
3. status = active 且 publishedAt ≤ now
4. permissionTags ⊆ user.permissionTags（任一交集即放行，AND 还是 OR 可配置）
5. 向量召回（HNSW COSINE, TopK=20）
6. Rerank → TopN=5
```

### 8.2 权限模型（§15.2）

- 用户：`userId + tenantId + permissionTags`
- 文档：`permissionTags[]`（可继承自知识库）
- 推荐：**AND** 默认（用户必须具备所有标签），通过 `@ConditionalOnProperty` 可切 OR

### 8.3 敏感信息脱敏（§15.3）

- 在 ContextAssembler 输出前，过滤正则：`\d{15,18}`（身份证）、`1[3-9]\d{9}`（手机号）、银行卡 Luhn
- 脱敏日志：原始 query 进 Answer 但不进 INFO 日志

## 9. 可观测性（§16）

### 9.1 Micrometer 指标（每条都实现）

```
rag.ingest.jobs.total{tenant,status}
rag.ingest.chunks.total{tenant}
rag.ingest.duration.ms{tenant}
rag.qa.requests.total{tenant,source}
rag.qa.cache.hit.ratio{tenant}
rag.qa.latency.ms{tenant,stage}  # stage=rewrite/embed/retrieve/rerank/generate
rag.qa.retrieved.chunks.count{tenant}
rag.qa.rerank.delta.score{tenant}
rag.qa.context.tokens{tenant}
rag.qa.degradation.total{tenant,reason}
rag.embedding.duration.ms{tenant,provider}
rag.embedding.cache.hit.ratio
rag.redis.hnsw.search.ms{tenant}
```

### 9.2 日志（MDC）

```
tenantId, userId, sessionId, queryHash, requestId,
stage=rewrite/embed/retrieve/rerank/generate,
retrieved=N, reranked=N, tokens=N, latencyMs, source
```

### 9.3 质量评估（§16.3）

- 离线评测集（`rag-test/src/test/resources/eval/`）：
  - 50 个 query，覆盖退款/物流/营销/规则/订单
  - 指标：Recall@K、MRR、Grounded Rate、引用覆盖率
- CI 跑：`mvn test -Peval`

## 10. 错误处理与韧性

| 异常 | 处理 | 降级 |
|---|---|---|
| DashScope Embedding 超时 | 重试 2 次 (1s, 3s) | 抛 `EmbeddingUnavailableException` → QA 返回 FALLBACK_RULE |
| DashScope LLM 超时 | 重试 1 次 | 返回基于检索片段拼接答案 |
| Redis 连接断开 | Lettuce 自动重连 + 熔断 30s | 整个 QA 链返回 503 + Retry-After |
| Redis 搜索 OOM | EF_RUNTIME 降级到 5 | 警告日志 |
| 重排服务不可用 | 跳过 Rerank 步 | 直接 TopK → TopN=min(TopK, 5) |
| 摄入 chunk 解析失败 | 标记该 chunk FAILED | 不阻塞整文档 |
| chunk 数超阈值 (单 doc > 10000) | 拒绝 + 提示拆分 | — |

## 11. 测试策略

### 11.1 单元测试 (Junit 5 + Mockito)

- `ChunkSplitterTest`：边界、空内容、长文、表格
- `ContextAssemblerTest`：token 预算、元数据保留、超预算压缩
- `RuleBasedQueryRewriterTest`：同义词、客套词、fallback
- `DashScopeEmbeddingGatewayTest`：Mock WebClient，验证重试 + 缓存
- `RedisIndexManagerTest`：Testcontainers，验证索引创建 + 切换
- `VectorRepositoryTest`：Testcontainers，验证过滤组合
- `QAServiceTest`：Mock 全链路，验证缓存命中、各项降级

### 11.2 集成测试 (Testcontainers)

- `RedisStackContainer` (redislabs/redistest:centos7)
- 真实 DashScope 调用：API Key 通过 `@EnabledIfEnvironmentVariable("DASHSCOPE_API_KEY")` 跳过

### 11.3 真实案例 demo（§18 — 退款规则问答）

- 端到端 test：`RefundRuleEndToEndTest`
- 步骤：上传退款规则 MD → 等摄入完成 → 提问"用户付了运费但商品质量问题退款，运费退吗" → 验证答案含"运费退还" + 引用 sourceUri

## 12. 部署（§17）

### 12.1 演进路径

1. **本地试点**（本机）：`docker-compose up` → 单实例 Redis Stack + 单实例 App
2. **小规模生产**：App 2 实例 + Redis Sentinel + DashScope
3. **中大型**：App K8s Deployment (HPA: CPU 70% / QPS 1000) + Redis Cluster + 独立 Embedding 服务池
4. **K8s 关注点**：Liveness/Readiness probe、PodDisruptionBudget、ResourceQuota、ConfigMap vs Secret 分层

### 12.2 docker-compose.yml

```yaml
services:
  redis:
    image: redis/redis-stack:7.4.0-v1
    ports: ["6379:6379", "8001:8001"]
  app:
    build: .
    depends_on: [redis]
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}
    ports: ["8080:8080"]
```

### 12.3 K8s 关键 probe

- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness` (依赖 Redis + DashScope)
- Startup probe：长 60s，给首次 embedding 索引留时间

## 13. 仓库与目录

- **GitHub**: `github.com/yysf1949/spring-ai-alibaba-rag` (**Private**)
- **本地根目录**: `/home/butterfly443/projects/spring-ai-alibaba-rag`
- **首次提交策略**: 空仓库 → `git init` → 推 skeleton → 然后用 PR 形式逐模块叠加（让 history 可读）

## 14. 落地步骤概览（详细 plan 走 writing-plans skill）

1. 自装 Maven / Redis Stack / 验证 JDK 21
2. 初始化 GitHub 私有仓库 + 本地 git
3. 创建 Maven 多 module 骨架
4. §13.1-13.4: 配置 + 领域模型 + Spring AI Alibaba starter 接入
5. §12: Redis 向量索引 + 元数据 + HNSW
6. §13.5-13.7: EmbeddingGateway + IndexManager + VectorRepository
7. §10.4: ChunkSplitter + §10.1-10.3: 摄入链
8. §11.2: RewriteService + §11.3: RerankService
9. §13.10: ContextAssembler + §13.11: QAService 编排
10. §13.12: 对外接口
11. §15: 多租户 + 权限 + 脱敏
12. §16: Micrometer + 日志 + 评估
13. §18: 退款规则 demo test
14. §17: Docker + docker-compose
15. §7 原则 + §19 坑位 + §20 演进 + §21 checklist：全部落到 docs/

## 15. 风险与缓解

| 风险 | 缓解 |
|---|---|
| DashScope API Key 延迟提供 | `application.yml` 用 `${DASHSCOPE_API_KEY:}` 占位；Embedding/LLM 用 `@ConditionalOnProperty` 优雅降级 |
| Redis Stack 拉镜像慢 / 国内拉不到 | 用 `mirror.gcr.io` 或 `docker.m.daocloud.io` 配 daemon.json |
| Spring AI Alibaba M6 API 不稳定 | 锁版本到 GA；文档中标注 |
| 22 节覆盖广，单 plan 写不完 | writing-plans 阶段会拆 phase-1 (MVP: ingest + 检索 + QA) / phase-2 (12 原则 + 可观测性 + 多租户完整版) / phase-3 (部署演进 + 真实案例 demo) |
| Maven 首次下载依赖慢 | 配 `~/.m2/settings.xml` 阿里云镜像 |

## 16. 验收标准（Definition of Done）

- [ ] `mvn clean verify` 全绿（包含 Testcontainers 集成测试）
- [ ] `mvn spring-boot:run` 启动后 `GET /actuator/health` 200
- [ ] `curl -X POST /ingest` 退款规则 MD → jobId → status=PUBLISHED
- [ ] `curl -X POST /qa` 提问 → 答案含 `运费退还` + 引用 `sourceUri`
- [ ] `docker-compose up` 一键起 Redis + App，端口 8080 可访问
- [ ] 22 节每节在 `docs/` 或代码注释里有对应入口
- [ ] README 含架构图（Mermaid）+ 快速开始 + 22 节映射表
- [ ] 全部代码已 push 到 `github.com/yysf1949/spring-ai-alibaba-rag` 私有仓库

---

## Spec Self-Review

- ✅ 无 TBD/TODO
- ✅ 内部一致：架构 vs 功能描述 vs 数据模型
- ✅ 范围聚焦：单 plan 能执行；如太大会在 writing-plans 阶段切 phase
- ✅ 无歧义：每个决策点都有具体数字/版本/路径
