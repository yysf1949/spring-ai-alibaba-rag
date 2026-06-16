# Metrics — spring-ai-alibaba-rag

> **Source of truth for the metric list**: spec §9.1 (the design document
> explicitly numbers them `rag.{area}.{name}`). This file is the operator
> view: what each metric means, what the labels are, what the unit is,
> and a sane alert threshold.

> **Status note**: the metric *names* below are stabilised. The *wiring*
> (Micrometer registration) lands in `rag-app` (Spring Boot module) which
> is **not yet shipped**. Until then, metrics are not emitted — only the
> `Answer.metrics` Map carries per-request timings in-process.

---

## 1. Conventions

- **Prefix**: `rag.*` (so Grafana dashboards can filter cleanly).
- **Unit suffix**: `.ms` for milliseconds, `.count` for counters,
  `.ratio` for [0,1] floats, `.bytes` for sizes. No suffix means "no
  fixed unit; check the metric description".
- **Common labels**:
  - `tenant` — owning tenant id. NEVER absent on a per-request metric.
  - `kb_id` — knowledge base id. Absent on rewrite/cache-only metrics.
  - `kb_version` — pinned version. Absent on rewrite-only metrics.
  - `source` — `cache | llm | fallback_rule` (matches `AnswerSource` enum).
  - `stage` — only on `rag.qa.latency.ms` — the pipeline leg.
  - `outcome` — `success | error` on error-tracking metrics.
- **Cardinality budget**: **never** put `user_id` or `query_text` on a
  label. Cardinality explosion is the #1 cause of Prometheus OOMs.

---

## 2. Ingest metrics

### `rag.ingest.documents.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Unit | documents |
| Labels | `tenant`, `kb_id`, `outcome` |
| Source | `IngestService` (§6.1) — one increment per document processed |

**Alert**: `rate(rag_ingest_documents_count{outcome="error"}[5m]) > 0.1` for 10m.
A non-trivial error rate means a chunking / store / index path is broken.

### `rag.ingest.chunks.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Unit | chunks |
| Labels | `tenant`, `kb_id`, `status` (`staging | active`) |
| Source | `IngestService` — one increment per chunk emitted by `ChunkSplitter` |

**Alert**: `rate(rag_ingest_chunks_count[5m]) / rate(rag_ingest_documents_count[5m])`
should stay in `[50, 500]` (i.e. 50-500 chunks per document). Below 50
suggests the splitter is collapsing content; above 500 suggests it's
fragmenting at the wrong boundary.

### `rag.ingest.duration.ms`

| Aspect | Value |
|---|---|
| Type | Histogram |
| Unit | milliseconds |
| Labels | `tenant`, `kb_id` |
| Buckets | `[100, 250, 500, 1000, 2500, 5000, 10000, 30000]` |

**Alert**: `histogram_quantile(0.95, rate(rag_ingest_duration_ms_bucket[5m])) > 10000`
means 95th percentile ingest takes >10s — investigate the Redis write path.

### `rag.ingest.failures.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `kb_id`, `error_type` (e.g. `vector_store_down`, `chunker_panic`) |
| Source | `IngestService` — incremented on every caught exception |

---

## 3. QA metrics

### `rag.qa.latency.ms`

| Aspect | Value |
|---|---|
| Type | Histogram |
| Labels | `tenant`, `kb_id`, `source`, `stage` |
| Buckets | `[25, 50, 100, 250, 500, 1000, 2500, 5000, 10000]` |

`stage` labels (per spec §9.1):

| Stage | What it measures |
|---|---|
| `rewrite` | `RewriteService.rewrite` total |
| `cache_check` | `AnswerCache.get` total |
| `embed` | `EmbeddingCache.get` + `EmbeddingGateway.embedBatch` total |
| `retrieve` | `VectorStore.search` total (server-side HNSW + filter) |
| `rerank` | `RerankService.rerank` total (DashScope gte-rerank) |
| `assemble` | `ContextAssembler.assemble` total |
| `generate` | `LlmService.generateAnswer` total |
| `cache_put` | `AnswerCache.put` total (best-effort) |

> **Implementation note**: `QAServiceImpl` already records per-stage
> timings in the `Answer.metrics` Map. The Micrometer wiring (to
> `Timer.record(...)` with the labels above) is a one-file addition in
> `rag-app`.

**Alert**: `histogram_quantile(0.95, rate(rag_qa_latency_ms_bucket{stage="generate"}[5m])) > 8000`
— LLM is taking >8s, the most common cause is DashScope rate-limiting.

### `rag.qa.requests.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `kb_id`, `source`, `outcome` |

**Health signal**: `sum by (source) (rate(rag_qa_requests_count[5m]))` should
be dominated by `source=llm` (or `source=cache` in steady state). A
sudden spike in `source=fallback_rule` means the LLM is down.

### `rag.qa.degradation.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `degradation_type` (one of `rerank_unavailable`, `llm_unavailable`, `empty_retrieval`, `cache_read_error`) |

This is the **"we served the user but with worse quality"** counter.
It should be near zero. Spikes map 1:1 to a downstream incident.

### `rag.qa.empty_retrieval.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `kb_id` |

> **NOTE**: this is currently emitted as a `stage.retrieval.empty`
> boolean in the `Answer.metrics` map — promote to a counter in
> `rag-app`.

---

## 4. Cache metrics

### `rag.cache.hit_ratio`

| Aspect | Value |
|---|---|
| Type | Gauge ([0, 1]) |
| Labels | `tenant`, `cache_name` (`rewrite | embedding | answer`) |
| Source | Each cache impl exposes `hitRatio(...)` — see spec §13.6, §13.7 |

**SLO**: hit ratio ≥ 0.4 within 24h of warm-up. Below 0.2 means either
the query distribution is very flat (no hot queries) or the cache is
too aggressively evicting (check TTL).

### `rag.cache.size.bytes`

| Aspect | Value |
|---|---|
| Type | Gauge |
| Labels | `cache_name` |

### `rag.cache.failures.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `cache_name`, `operation` (`get | put`) |

**Alert**: any non-zero rate. Cache failures are silent (`QAServiceImpl`
treats them as miss), so the only way to spot them is this counter.

---

## 5. Embedding metrics

### `rag.embedding.duration.ms`

| Aspect | Value |
|---|---|
| Type | Histogram |
| Labels | `tenant`, `model`, `cache_outcome` (`hit | miss`) |
| Buckets | `[5, 10, 25, 50, 100, 250, 500]` |

The `cache_outcome=miss` is the **only** leg that actually costs money
(DashScope API call). The hit path is just a Redis GET.

### `rag.embedding.daily_tokens.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `model` |

**Alert**: `rate(rag_embedding_daily_tokens_count[1d]) > 5_000_000` —
blow the per-day budget alarm. DashScope tier-1 default is 1M tokens/day.

---

## 6. Rerank metrics

### `rag.rerank.duration.ms`

| Aspect | Value |
|---|---|
| Type | Histogram |
| Labels | `tenant`, `model`, `fallback_used` (`true | false`) |
| Buckets | `[25, 50, 100, 250, 500, 1000, 2500]` |

`fallback_used=true` means the rerank call failed and `QAService`
served the top-K raw — counted separately so dashboards can split.

### `rag.rerank.failures.count`

| Aspect | Value |
|---|---|
| Type | Counter |
| Labels | `tenant`, `error_type` |

---

## 7. Quality / evaluation metrics (spec §9.3)

> ⛔ **Not yet implemented.** Tracked in `rag-app` after 5-P4.

| Metric | Type | Source |
|---|---|---|
| `rag.eval.answer_relevance.score` | Gauge [0, 1] | RAGAS-style eval job |
| `rag.eval.context_precision.score` | Gauge [0, 1] | RAGAS-style eval job |
| `rag.eval.faithfulness.score` | Gauge [0, 1] | RAGAS-style eval job |
| `rag.eval.human_review.outcome` | Counter (`correct | incorrect | unverified`) | Manual review app |

These run on a **separate schedule** (e.g. nightly 1% sample), not on
the hot path — keep them out of the request-time cardinality.

---

## 8. Recommended Grafana dashboard layout

```
┌──────────────────────────────────────────────────────────────┐
│  Row 1: SLO at a glance                                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ QA p95   │  │ QA p99   │  │ Cache    │  │ Error    │      │
│  │ latency  │  │ latency  │  │ hit %    │  │ rate     │      │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘      │
│                                                               │
│  Row 2: Ingest health                                         │
│  ┌──────────────────────────┐  ┌─────────────────────┐      │
│  │ Docs/sec by tenant       │  │ Chunks/doc ratio    │      │
│  └──────────────────────────┘  └─────────────────────┘      │
│                                                               │
│  Row 3: QA breakdown                                          │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ Stacked area: source={cache,llm,fallback_rule}        │    │
│  └──────────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ Heatmap: rag.qa.latency.ms by stage                   │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  Row 4: Degradation alarms                                    │
│  ┌──────────────────┐  ┌──────────────────┐                   │
│  │ Degradation rate │  │ LLM timeout rate │                   │
│  └──────────────────┘  └──────────────────┘                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 9. Where to add a new metric

When adding a metric:

1. **Add it here first** (this file), under the right section.
2. **Update the spec** (`docs/superpowers/specs/...`) if the metric
   represents a new product surface (not just an implementation detail).
3. **Add the Timer / Counter in `rag-app`** when Spring wiring lands.
4. **Add a Grafana panel** in the dashboard.
5. **Add a smoke check** that the metric is actually emitted (one
   end-to-end request → assert the counter incremented).
