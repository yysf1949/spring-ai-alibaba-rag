# Production Checklist — spring-ai-alibaba-rag

> 设计 Spec §21 — 生产落地 checklist
>
> 来源文章 §21 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 1. 代码质量 (Code Readiness)

### 1.1 编译 / 测试

- [ ] `mvn clean verify` 全绿 (含 IT)
- [ ] `mvn test -Peval` 全绿 (Recall@K / MRR 不退化)
- [ ] 单元测试覆盖率 > 60% (P0 module > 80%)
- [ ] 无 `@SuppressWarnings("unchecked")` 滥用
- [ ] 无 `System.out.println` (必须用 SLF4J)
- [ ] 无 hard-coded URL / API key (必须走 yml + env var)

### 1.2 代码风格

- [ ] Checkstyle / Spotless 通过 (如配置)
- [ ] Lombok `@Slf4j` 替代手写 logger
- [ ] Java 21 records 用于不可变 model
- [ ] 包结构按 `io.github.yysf1949.rag.{module}.{subpackage}`

### 1.3 文档

- [ ] `README.md` 含架构图 + 快速开始 + 16 节映射
- [ ] `docs/RUNBOOK.md` 含本地 + Docker 操作
- [ ] `docs/LESSONS.md` 更新到当前 (含新踩的坑)
- [ ] `docs/METRICS.md` 与代码同步 (新指标必须补文档)
- [ ] `docs/MULTI_TENANT.md` 与代码同步 (新权限规则必须补文档)
- [ ] spec `docs/superpowers/specs/` 与实现同步 (DoD 8 条全过)

---

## 2. 安全 (Security)

### 2.1 认证 / 授权

- [ ] API 入口有 tenantId 校验 (`MdcTenantFilter`)
- [ ] userId / sessionId 来自可信 header (不取自 body,防止伪造)
- [ ] `permissionTags` 强制 ≥ 1 (不允许空集放行)
- [ ] Admin API 单独鉴权 (OAuth2 / JWT,非 tenant filter)

### 2.2 数据加密

- [ ] TLS 1.2+ (Redis / DashScope / 内部 RPC)
- [ ] API key / Secret 走 K8s Secret (非 ConfigMap)
- [ ] Secret 不进 git (`.gitignore` 覆盖 `.env` `*.key` `*.pem`)
- [ ] 落盘 Redis 数据加密 (如有合规要求)

### 2.3 注入 / 越权

- [ ] 所有外部输入走 JSR-303 validation (`@Valid` `@NotBlank` `@Pattern`)
- [ ] Redis search 过滤层强制包含 `tenantId` (代码 review 必检)
- [ ] SQL 不存在 (我们用 Redis,但要 review RediSearch query 是否用户可控)
- [ ] XSS 防护 (Spring Boot 默认 HTML escape,review prompt injection 风险)

### 2.4 审计日志

- [ ] Admin 操作 (知识库发布 / 租户开关) 有 audit log
- [ ] LLM 调用记录 queryHash + 答案 (可回溯,GDPR 合规)
- [ ] 敏感操作 4-eyes principle (双签)

---

## 3. 可观测 (Observability)

### 3.1 指标 (Metrics)

- [ ] `rag.qa.*` 全套 13 个指标暴露
- [ ] `rag.ingest.*` 全套指标暴露
- [ ] `rag.cache.*` hit ratio gauge 暴露
- [ ] `rag.embedding.*` 耗时 + token 计数
- [ ] `resilience4j_*` circuit breaker / rate limiter 暴露
- [ ] Prometheus 抓取配置 + Grafana dashboard 配好
- [ ] AlertManager 告警规则覆盖: high latency / breaker open / cache hit ratio low / empty retrieval

### 3.2 日志 (Logs)

- [ ] MDC 字段全: `tenantId` `userId` `sessionId` `queryHash` `requestId` `stage`
- [ ] logback-spring.xml 用 JSON encoder (production profile)
- [ ] 日志级别 INFO (production), DEBUG 仅 development profile
- [ ] PII 脱敏 (身份证 / 手机号 / 银行卡) 在日志输出前
- [ ] 日志采样率可配置 (避免 QPS 高时日志爆炸)
- [ ] ELK / Loki 接入,索引 `tenantId` `stage`

### 3.3 链路追踪 (Tracing)

- [ ] OpenTelemetry SDK 接入 (后续 Phase)
- [ ] requestId 全链路传递 (HTTP → Controller → Pipeline → Redis → LLM)
- [ ] Span: rewrite / embed / retrieve / rerank / generate 5 段

### 3.4 告警 (Alerting)

- [ ] P95 latency > 3s 持续 5min → Warning
- [ ] CircuitBreaker OPEN 持续 1min → Critical
- [ ] Cache hit ratio < 30% 持续 10min → Warning
- [ ] Empty retrieval rate > 10% 持续 5min → Warning
- [ ] Eval regression > 5% → Critical (CI 阶段)
- [ ] Redis 内存 > 80% → Warning
- [ ] DashScope API 4xx/5xx rate > 5% → Warning

---

## 4. 性能 (Performance)

### 4.1 容量规划

- [ ] 单实例 QPS 容量测试 (baseline 100 QPS)
- [ ] P95 / P99 latency benchmark (目标 < 1.5s)
- [ ] Redis 内存估算 (chunks × 4KB + overhead)
- [ ] Embedding provider QPS 上限 (DashScope 100 / SiliconFlow 200)

### 4.2 压力测试

- [ ] 1000 QPS 持续 10 分钟 (集群),无 OOM / 无 5xx
- [ ] 10000 QPS 突发 1 分钟,熔断正常跳闸
- [ ] 50 并发摄入大文档 (10K chunks/doc),无 ingest job 失败
- [ ] Redis failover < 30s,应用自动恢复
- [ ] LLM provider 故障模拟 (mock),降级正常,无 5xx 风暴

### 4.3 优化

- [ ] AnswerCache 命中 > 50% (业务侧 metric)
- [ ] EmbeddingCache 命中 > 70%
- [ ] ContextAssembler token 预算不超 4000
- [ ] 无 N+1 查询 (RediSearch 一次性 TopK)
- [ ] JVM GC G1 / ZGC,无 Full GC > 1s

---

## 5. 数据 (Data)

### 5.1 多租户

- [ ] tenantId 在所有 read/write API 强制
- [ ] kbId 白名单在 Controller 校验
- [ ] permissionTags 默认 AND 语义 (按需可改 OR)
- [ ] 跨租户测试覆盖 (tenant A 查不到 tenant B 的数据)

### 5.2 备份 / 恢复

- [ ] Redis AOF 每秒 fsync
- [ ] 跨 region Redis 复制 (P2 起)
- [ ] 知识库元数据备份 (每周全量 + 每日增量)
- [ ] 恢复演练 (季度一次)

### 5.3 数据生命周期

- [ ] DEPRECATED chunk 保留 7 天后清理 (cron)
- [ ] AnswerCache TTL 1h (可配置)
- [ ] EmbeddingCache 清理孤儿 (无引用 > 30 天的 embedding)
- [ ] 日志保留 30 天 + 归档到对象存储
- [ ] 指标保留 1 年 (Prometheus) + downsampling

---

## 6. 韧性 (Resilience)

### 6.1 Circuit Breaker

- [ ] Redis 断路器配置 (slidingWindow=10, failureRate=50%)
- [ ] SiliconFlow 断路器配置 (同上)
- [ ] 断路器跳闸后自动测试恢复 (HALF_OPEN)
- [ ] 断路器状态变更告警

### 6.2 Rate Limiter

- [ ] QPS 入口限流 (100/instance/s, 可配置)
- [ ] 限流触发 HTTP 429 + Retry-After header
- [ ] 限流状态监控

### 6.3 降级 (Degradation)

- [ ] LLM 失败 → FALLBACK_RULE (拼接检索片段)
- [ ] Rerank 失败 → 跳过,直接 TopK→TopN
- [ ] Redis 失败 → 503 + Retry-After
- [ ] Embedding 失败 → CircuitBreaker OPEN → 降级

### 6.4 重试 / 超时

- [ ] WebClient 重试 2 次,指数 backoff (1s, 3s)
- [ ] Redis 操作超时 5s (可配置)
- [ ] LLM 调用超时 30s
- [ ] 全链路总超时 60s

---

## 7. 部署 (Deployment)

### 7.1 镜像

- [ ] 基础镜像: `eclipse-temurin:21-jre-jammy`
- [ ] 多阶段构建 (减小体积)
- [ ] `.dockerignore` 排除 target/ .git/
- [ ] Aliyun Maven mirror (国内下载)
- [ ] 健康检查: liveness + readiness + startup
- [ ] 资源限制: CPU 2 + memory 4Gi

### 7.2 K8s

- [ ] Deployment + Service + ConfigMap + Secret
- [ ] HPA (CPU 70% + 自定义 QPS 指标)
- [ ] PDB minAvailable ≥ 2
- [ ] NetworkPolicy (限制 pod-to-pod 流量)
- [ ] ServiceAccount + RBAC
- [ ] ResourceQuota (命名空间级)
- [ ] Ingress (TLS 终止)
- [ ] Helm chart 标准化

### 7.3 CI/CD

- [ ] PR 触发: lint + test + build
- [ ] main push: test + build + docker push + deploy to staging
- [ ] tag 触发: deploy to production (manual approval)
- [ ] 回滚流程 (helm rollback / kubectl rollout undo)
- [ ] 数据库迁移 (Redis 无 schema,但 Key 版本兼容)

---

## 8. 运维 (Operations)

### 8.1 文档

- [ ] RUNBOOK 含本地 + Docker + 故障排查
- [ ] LESSONS 含已踩过的坑
- [ ] FAQ 含高频问题
- [ ] 架构图 + 16 节映射
- [ ] 变更日志 (CHANGELOG.md)

### 8.2 监控 dashboard

- [ ] QA QPS (含 source 维度)
- [ ] P95 / P99 latency (按 stage)
- [ ] Cache hit ratio
- [ ] Circuit breaker state
- [ ] Redis 内存 / 连接数
- [ ] Embedding token 消耗 / 日
- [ ] 降级率 (FALLBACK_RULE 占比)
- [ ] Eval 指标 (Recall@K / MRR,周报)

### 8.3 应急流程 (Runbook)

- [ ] Redis 挂: 自动恢复 (< 30s) → 监控告警 → 人工确认
- [ ] SiliconFlow 限流: CircuitBreaker OPEN → 降级 → 联系 provider
- [ ] LLM 返回幻觉: 已知问题,FALLBACK_RULE 兜底 → 收集样本 → 后续优化
- [ ] 知识库污染 (错误数据摄入): 立即 revert → 重新发布 → 审计

---

## 9. 合规 (Compliance)

- [ ] GDPR / 个人信息保护法: PII 脱敏 + 用户删除接口
- [ ] 数据出境: 国内 region + 不出境承诺
- [ ] 知识产权: 用户输入/输出归用户所有
- [ ] 审计日志: 6 个月保留 (监管要求)
- [ ] 服务等级协议 (SLA): 99.5% 可用性承诺
- [ ] 第三方依赖许可: Spring Boot (Apache 2.0) / Redis (BSD) / SiliconFlow (ToS)

---

## 10. 业务验收 (Business Acceptance)

- [ ] §18 退款规则 E2E test 通过
- [ ] Eval 套件 49 条 fixture 通过率 > 90%
- [ ] 业务方 demo 满意 (引用准确率 + 答案质量)
- [ ] 业务方培训完成 (admin 操作 / 知识库维护)
- [ ] SLA 文档 + 应急联系人清单

---

## 11. 上线签字 (Sign-off)

| 角色 | 姓名 | 签字 | 日期 |
|---|---|---|---|
| Tech Lead | ___ | ___ | ____ |
| SRE | ___ | ___ | ____ |
| 产品 | ___ | ___ | ____ |
| 安全 | ___ | ___ | ____ |

---

## 12. 参考

- [docs/RUNBOOK.md](./RUNBOOK.md) — 本地 + Docker 操作
- [docs/architecture.md](./architecture.md) — 整体架构
- [docs/deployment.md](./deployment.md) — 部署细节
- [docs/evolution.md](./evolution.md) — 演进路径
- [docs/faq.md](./faq.md) — 高频坑
