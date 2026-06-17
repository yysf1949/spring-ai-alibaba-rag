# Observability — spring-ai-alibaba-rag

> 设计 Spec §16 — 指标体系 + 日志 + 评估
>
> 来源文章 §16 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 1. 三层可观测

| 层 | 工具 | 落地 |
|---|---|---|
| **指标 (Metrics)** | Micrometer + Prometheus | `rag-app` actuator `/actuator/prometheus` |
| **日志 (Logs)** | SLF4J + Logback + MDC | `PipelineMdc` 全链路 stage 标签 |
| **评估 (Eval)** | `EvaluationService` + Recall@K/MRR | `mvn test -Peval` + `docs/eval/` |

---

## 2. Micrometer 指标全集

### 2.1 摄入链 (Ingest)

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `rag.ingest.documents.count` | Counter | tenant, status | 摄入文档总数 |
| `rag.ingest.chunks.count` | Counter | tenant | 切出的 chunk 数 |
| `rag.ingest.duration.ms` | Timer | tenant | 摄入总耗时 |
| `rag.ingest.failures.count` | Counter | tenant, reason | 摄入失败计数 |

### 2.2 在线链 (QA)

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `rag.qa.requests.count` | Counter | tenant, source | 请求总数 (source=CACHE/LLM/FALLBACK_RULE) |
| `rag.qa.latency.ms` | Timer | tenant, stage | 各 stage 耗时 (rewrite/embed/retrieve/rerank/generate) |
| `rag.qa.cache.hit.ratio` | Gauge | tenant, type | Answer/Embedding cache hit ratio |
| `rag.qa.retrieved.chunks.count` | DistributionSummary | tenant | 召回 chunk 数 |
| `rag.qa.context.tokens` | DistributionSummary | tenant | Context token 数 |
| `rag.qa.rerank.delta.score` | DistributionSummary | tenant | Rerank 前后的 score 差 |
| `rag.qa.degradation.count` | Counter | tenant, reason | 降级次数 |
| `rag.qa.empty_retrieval.count` | Counter | tenant | 召回为空次数 |

### 2.3 Embedding

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `rag.embedding.duration.ms` | Timer | tenant, provider | Embedding 调用耗时 |
| `rag.embedding.cache.hit.ratio` | Gauge | — | Embedding cache hit ratio |
| `rag.embedding.daily_tokens.count` | Counter | tenant | 当日 token 消耗 |

### 2.4 Redis

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `rag.redis.hnsw.search.ms` | Timer | tenant | HNSW 搜索耗时 |
| `rag.cache.size.bytes` | Gauge | tenant, type | 缓存占用字节数 |
| `rag.cache.failures.count` | Counter | tenant, type | 缓存失败计数 |

---

## 3. MDC 日志字段 (Cluster 5 落地)

```java
// rag-pipeline/logging/PipelineMdc.java
public final class PipelineMdc {
    public static final String TENANT_ID  = "tenantId";
    public static final String USER_ID    = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String QUERY_HASH = "queryHash";
    public static final String REQUEST_ID = "requestId";
    public static final String STAGE      = "stage";
    public static final String RETRIEVED  = "retrieved";
    public static final String RERANKED   = "reranked";
    public static final String TOKENS     = "tokens";
    public static final String LATENCY_MS = "latencyMs";
    public static final String SOURCE     = "source";
}
```

### 3.1 日志输出格式 (logback-spring.xml)

```xml
<pattern>
  %d{ISO8601} %-5level [%X{tenantId:-}][%X{userId:-}][%X{requestId:-}]
  [stage=%X{stage:-}][retrieved=%X{retrieved:-0}][tokens=%X{tokens:-0}]
  [%thread] %logger{36} - %msg%n
</pattern>
```

### 3.2 典型日志

```
2026-06-17 13:45:22 INFO [t1][u42][req-7a3f]
[stage=retrieve][retrieved=8][tokens=0]
[http-nio-8080-exec-3] io.github.yysf1949.rag.pipeline.qa.QAServiceImpl -
HNSW search returned 8 chunks in 23 ms
```

---

## 4. Resilience4j 指标

Resilience4j 自动暴露以下指标 (无需额外配置,只要 actuator + prometheus 在):

| 指标名 | 含义 |
|---|---|
| `resilience4j_circuitbreaker_state` | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |
| `resilience4j_circuitbreaker_calls_total{kind="successful"}` | 成功调用数 |
| `resilience4j_circuitbreaker_calls_total{kind="failed"}` | 失败调用数 |
| `resilience4j_circuitbreaker_calls_total{kind="not_permitted"}` | 短路调用数 |
| `resilience4j_ratelimiter_available_permissions` | 剩余许可数 |

Grafana dashboard 推荐:
```promql
# P95 latency by stage
histogram_quantile(0.95,
  sum by (le, stage) (rate(rag_qa_latency_ms_seconds_bucket[5m])))

# Cache hit ratio
rag_qa_cache_hit_ratio{type="answer"}

# CircuitBreaker state (alert if != 0 for 1 min)
resilience4j_circuitbreaker_state{name="redis"}
```

---

## 5. 评估 (Eval) — Cluster 6-A 落地

### 5.1 EvaluationService

```java
// rag-test/src/main/java/.../eval/EvaluationService.java
public record EvalResult(
    String fixtureId,
    boolean retrieved,       // 命中 expectedChunks 至少 1 个
    double recallAtK,        // 命中的 expected / total expected
    double mrr,              // 首个正确 chunk 的倒数排名
    boolean groundedRate,    // answer 中的引用都来自 retrieved chunks
    long latencyMs
) {}

public class EvaluationService {
    public EvalReport run(List<EvalFixture> fixtures, QAService qa) {
        // ...Recall@K, MRR, GroundedRate 计算
    }
}
```

### 5.2 Fixture 格式

```json
// docs/eval/refund-shipping-fee.json
{
  "id": "refund-shipping-fee",
  "tenantId": "demo",
  "kbId": "refund-rules",
  "query": "用户付了运费但商品质量问题退款,运费退吗",
  "expectedChunks": ["refund-rule-001"],
  "expectedAnswerContains": ["运费退还"],
  "expectedCitationCount": 1
}
```

### 5.3 运行方式

```bash
mvn test -Peval
# → docs/eval/report.json
# → 49 fixtures,Recall@K / MRR / GroundedRate 平均值
```

### 5.4 CI 接入 (后续 Phase)

```yaml
# .github/workflows/eval.yml (待加)
- name: Run eval
  run: mvn test -Peval
- name: Compare to baseline
  run: |
    jq '.recallAtK' docs/eval/report.json > current.json
    if [ $(jq -s '.[0] < .[1]' baseline.json current.json) = "true" ]; then
      echo "Eval regression!"
      exit 1
    fi
```

---

## 6. Prometheus 接入

### 6.1 application.yml

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        rag.qa.latency.ms: true
  prometheus:
    metrics:
      export:
        enabled: true
```

### 6.2 抓取端点

```bash
curl http://localhost:8080/actuator/prometheus | head -20
# HELP rag_qa_requests_total
# TYPE rag_qa_requests_total counter
rag_qa_requests_total{tenant="demo",source="LLM"} 42.0
```

### 6.3 Grafana Dashboard (建议 panel)

| Panel | PromQL |
|---|---|
| QA QPS | `rate(rag_qa_requests_total[1m])` |
| Cache hit ratio | `rag_qa_cache_hit_ratio` |
| P95 latency by stage | `histogram_quantile(0.95, sum by (le, stage) (rate(rag_qa_latency_ms_seconds_bucket[5m])))` |
| Circuit breaker state | `resilience4j_circuitbreaker_state` |
| Top tenants by QPS | `topk(5, sum by (tenant) (rate(rag_qa_requests_total[5m])))` |
| Eval regression alert | `delta(rag_eval_recall_at_k[1d]) < -0.05` |

---

## 7. 告警规则 (Prometheus AlertManager)

```yaml
groups:
  - name: rag_alerts
    rules:
      - alert: RAGHighLatency
        expr: histogram_quantile(0.95, rate(rag_qa_latency_ms_seconds_bucket[5m])) > 3000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "P95 QA latency > 3s"

      - alert: RAGCircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Circuit breaker {{ $labels.name }} OPEN"

      - alert: RAGLowCacheHitRatio
        expr: rag_qa_cache_hit_ratio < 0.3
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Cache hit ratio below 30%"

      - alert: RAGEmptyRetrieval
        expr: rate(rag_qa_empty_retrieval_count[5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Empty retrieval rate > 10%"
```

---

## 8. 与 docs/METRICS.md 的关系

[docs/METRICS.md](./METRICS.md) 是 **指标清单 (what)**,本文是 **接入 + 告警 (how)**。两份互补。

[docs/LESSONS.md §13](./LESSONS.md) — 实战中 MDC 接入的 7 个教训。
