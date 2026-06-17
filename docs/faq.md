# FAQ — spring-ai-alibaba-rag

> 设计 Spec §19 — 高频坑
>
> 来源文章 §19 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战
>
> **本文聚焦"遇到过的问题 + 根因 + 解决",更细的 dev diary 见 [docs/LESSONS.md](./LESSONS.md)**

---

## 1. 摄入链相关

### Q1.1: `POST /api/ingest` 返回 401 缺 tenantId

**症状**:
```
HTTP 401 Unauthorized
{"error":"missing X-Tenant-Id header"}
```

**根因**: `MdcTenantFilter` 强制要求 `X-Tenant-Id` header,缺则拒绝。

**解决**:
```bash
curl -X POST http://localhost:8080/api/ingest \
    -H 'Content-Type: application/json' \
    -H 'X-Tenant-Id: demo' \
    -d '{"kbId":"refund-rules",...}'
```

---

### Q1.2: Ingest job 一直 PENDING 不变 READY

**症状**: `GET /api/ingest/{jobId}` 5 分钟后仍 `PENDING`。

**排查**:
1. 看 `IngestJobExecutor` 线程池是否启动
   ```bash
   curl http://localhost:8080/actuator/metrics/rag.ingest.documents.count
   ```
2. 看 Embedding 调用是否报错
   ```bash
   grep "stage=ingest" logs/*.log | tail -20
   ```
3. 看 Redis 索引是否创建
   ```bash
   redis-cli FT._LIST
   ```

**常见原因**:
- EmbeddingGateway 抛 `EmbeddingUnavailableException`(SiliconFlow 限流)
- Redis 写失败(连接断开)
- Staging index 名字格式错(不能含特殊字符)

---

### Q1.3: Chunk 数比预期少很多

**症状**: 一个 100 KB 文档只切出 5 chunks,预期应该 20+。

**根因**: `ChunkSplitter` 默认 maxTokens=500,大段未分割。

**解决**: 调整 `spring.rag.ingest.chunk.max-tokens`:
```yaml
spring:
  rag:
    ingest:
      chunk:
        max-tokens: 800
        overlap-tokens: 50
```

---

## 2. QA 链相关

### Q2.1: `POST /api/qa` 返回 503 "vector-store-unavailable"

**症状**:
```
HTTP 503 Service Unavailable
Retry-After: 30
{"error":"vector-store-unavailable"}
```

**排查**:
1. Redis 连接是否正常
   ```bash
   docker exec rag-redis-stack redis-cli PING
   # → PONG
   ```
2. CircuitBreaker 状态
   ```bash
   curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state
   ```
3. KB 是否发布
   ```bash
   redis-cli GET rag:publish:demo:refund-rules
   # → "1" (kbVersion)
   ```

**常见原因**:
- ❌ 没发布:`rag:publish:{tenant}:{kbId}` 不存在 → 先 `POST /api/ingest/{jobId}/publish`
- ❌ 版本不对:chunk 的 `documentVersion=5`,但 publish pointer=1 → 重 publish
- ❌ Redis 挂了:CircuitBreaker OPEN → 等 30s 自动重试

---

### Q2.2: 答案不引用任何文档 (citation 列表为空)

**症状**:
```json
{
  "finalText": "...",
  "citations": []
}
```

**根因**: `ContextAssembler` 没拼 sourceUri 或 Rerank 把相关文档过滤掉了。

**排查**:
1. 看 retrieved chunks 是否含 content
   ```bash
   curl http://localhost:8080/actuator/metrics/rag.qa.retrieved.chunks.count
   ```
2. 看 Rerank delta score
   ```bash
   curl http://localhost:8080/actuator/metrics/rag.qa.rerank.delta.score
   ```
3. 检查 ContextAssembler 是否 trim 了 metadata
   ```bash
   grep "context_tokens" logs/*.log | tail -20
   ```

---

### Q2.3: 答案质量差,经常"答非所问"

**症状**: 用户问"运费退吗",LLM 答"商品 7 天无理由退货"。

**可能原因**:
1. **Embeddings 不准** — 检查 chunk 是否被正确切分,标题/段落是否完整
2. **TopK 太小** — 默认 20,试试 50
3. **Rerank 没启用** — 检查 RerankService bean 是否注入
4. **Prompt 模板问题** — 检查 `DefaultPromptTemplate`

**调优方向**:
```yaml
spring:
  rag:
    qa:
      topk: 50           # 默认 20
      topn: 5            # 默认 5
      context:
        max-tokens: 4000
        system-prompt: |
          你是一个专业的客服助手,必须基于以下文档回答...
```

---

## 3. 多租户相关

### Q3.1: 租户 A 能查到租户 B 的数据

**症状**: tenantId 强制隔离测试失败。

**根因**: Redis search 过滤漏了 `tenantId` 或 chunk 的 `tenantId` 写错了。

**排查**:
```bash
# 看实际 chunk 的 tenantId
redis-cli HGETALL rag:chunk:demo:abc123 | grep tenantId

# 看搜索是否带 tenantId 过滤
grep "FT.SEARCH" logs/*.log
```

**教训** (MULTI_TENANT.md §1): tenantId 过滤必须在 **adapter 层** (`RedisVectorStore.search`),不能依赖上游 (`QAServiceImpl`),因为任何 caller 都能绕过。

---

### Q3.2: permissionTags 过滤太严格,用户查不到任何文档

**症状**: 用户有 `["finance"]` 权限,但知识库 chunk 的标签是 `["finance", "internal"]`,AND 语义下查不到。

**解决**: 改 OR 语义 (配置):
```yaml
spring:
  rag:
    tenant:
      permission-mode: OR   # 默认 AND
```

---

## 4. 性能相关

### Q4.1: P95 latency 突然飙升到 5s

**排查顺序**:
1. **Stage 拆分**: 看是哪个 stage 慢
   ```bash
   curl http://localhost:8080/actuator/metrics/rag.qa.latency.ms?tag=stage:retrieve
   ```
2. **常见慢 stage**:
   - `embed` → SiliconFlow 限流
   - `retrieve` → Redis HNSW 搜索慢 (EF_RUNTIME 太高 / Index 没加载完)
   - `rerank` → SiliconFlow 限流
   - `generate` → LLM 慢
3. **CircuitBreaker 状态**:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

---

### Q4.2: AnswerCache hit ratio 一直是 0

**根因**:
- queryHash 计算不一致 (改过 Query 对象字段)
- Redis 写 cache 失败 (连接断开)
- Cache TTL 太短 (< 查询间隔)

**排查**:
```bash
# 看 cache 写入次数
curl http://localhost:8080/actuator/metrics/rag.cache.writes.count

# 看 cache 读次数
curl http://localhost:8080/actuator/metrics/rag.cache.reads.count

# 计算 hit ratio = reads / (reads + miss)
```

---

### Q4.3: 摄入大文档 (10K chunks) 时 OOM

**症状**: `IngestJobExecutor` 线程 OOM,Killed by Kubernetes。

**根因**: 单次 `embedBatch()` 太大,内存爆炸。

**解决**:
```yaml
spring:
  rag:
    embedding:
      batch-size: 32          # 默认 100,降
      max-concurrent-batches: 2
```

---

## 5. 部署相关

### Q5.1: Docker build 卡在 Maven 依赖下载

**根因**: 网络不通 Maven Central。

**解决**: Dockerfile 用 Aliyun mirror (已配置 `.docker-maven/settings.xml`)。

---

### Q5.2: docker compose up 后 app 起不来

**排查**:
```bash
docker compose logs app | tail -50
```

**常见错误**:
- `redis host not found` → 改 `host.docker.internal` (Mac/Win) 或 `172.17.0.1` (Linux)
- `SILICONFLOW_API_KEY required` → 设置环境变量或留空走 stub
- `Permission denied` → chmod +x scripts/build-docker.sh

---

### Q5.3: K8s pod 一直 CrashLoopBackOff

**排查**:
```bash
kubectl logs <pod-name> --previous
kubectl describe pod <pod-name>
```

**常见原因**:
- OOMKilled → 调大 memory limit
- Redis 连接不上 → 检查 Service + NetworkPolicy
- Liveness probe 失败 → 调长 `initialDelaySeconds`

---

## 6. 测试相关

### Q6.1: `mvn test` 在 CI 跑超 10 分钟

**根因**: Testcontainers 每次拉镜像。

**解决**:
1. CI 缓存镜像 (`docker save` / `docker load`)
2. 用 fallback localhost Redis (cluster 3 plan 决策)
3. 拆分 fast tests / slow IT

---

### Q6.2: Eval 套件突然 Recall@K 下降

**排查**:
1. 看哪些 fixture 失败
2. 看 chunk 是否被重新切分
3. 看 embedding model 是否变了
4. 对比 git log 看最近改了什么

---

## 7. 韧性相关 (Cluster 6 落地后)

### Q7.1: CircuitBreaker 一直 OPEN 不恢复

**排查**:
```bash
curl http://localhost:8080/actuator/health/resilience4j
```

**手动 reset** (不推荐):
```bash
curl -X POST http://localhost:8080/actuator/circuitbreakers/redis
```

**正确做法**: 等 `waitDurationInOpenState` (默认 10s) → 自动转 HALF_OPEN → 试探 → CLOSED。

---

### Q7.2: RateLimiter 触发 429 但客户端无感

**症状**: 客户端看到 429 但 server 日志没明显异常。

**排查**:
1. 看 rate limiter 状态
   ```bash
   curl http://localhost:8080/actuator/metrics/resilience4j.ratelimiter.available.permissions
   ```
2. 调 limit 阈值
   ```yaml
   resilience4j:
     ratelimiter:
       instances:
         qa:
           limit-for-period: 200     # 默认 100
           limit-refresh-period: 1s
   ```

---

## 8. 常见错误信息速查

| 错误 | 含义 | 解决 |
|---|---|---|
| `cannot find symbol class LlmService` | rag-embedding 依赖没引入 | `mvn -pl rag-pipeline -am compile` |
| `RedisCommandTimeoutException: FT.SEARCH` | RediSearch 查询超时 | 调 `timeout` 或降 `EF_RUNTIME` |
| `AnswerCache hit ratio stuck at 0` | 缓存未命中 | 检查 queryHash 一致性 |
| `Sensitive data redaction test failing` | 正则没匹配 | 检查 input 是否含中文 |
| `git push rejected: non-fast-forward` | 远端有新 commit | `git pull --rebase` |
| `rag-qa returns 503 with no error in logs` | CircuitBreaker OPEN | 等自动恢复或检查下游 |

---

## 9. 仍未解决 / 已知问题

| 问题 | 状态 | 影响 |
|---|---|---|
| K8s Helm chart 缺失 | 计划 P0 | 部署需手写 manifest |
| 多语言 i18n 未做 | 计划 P1 | 仅支持中文 |
| 流式输出未做 | 计划 P1 | LLM 长答案体验差 |
| Tracing 未接 OpenTelemetry | 计划 P2 | 排查跨服务问题困难 |

详见 [docs/evolution.md](./evolution.md)。

---

## 10. 参考

- [docs/LESSONS.md](./LESSONS.md) — 14 节实战教训 (dev diary, 更细)
- [docs/RUNBOOK.md](./RUNBOOK.md) — 操作手册
- [docs/MULTI_TENANT.md](./MULTI_TENANT.md) — 多租户契约
- [docs/METRICS.md](./METRICS.md) — 指标清单
- [docs/checklist.md](./checklist.md) — 上线 checklist
