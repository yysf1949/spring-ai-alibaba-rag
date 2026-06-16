# spring-ai-alibaba-rag — Documentation

| File | Audience | What it covers |
|---|---|---|
| [RUNBOOK.md](./RUNBOOK.md) | Operator | Local boot · docker-compose · smoke test · troubleshooting |
| [LESSONS.md](./LESSONS.md) | Developer | Real issues + root causes + deployment checklist (dev diary) |
| [METRICS.md](./METRICS.md) | Operator / SRE | All Prometheus metrics exposed by the pipeline (spec §9.1) |
| [MULTI_TENANT.md](./MULTI_TENANT.md) | Operator / product | Tenant contract · permission model · PII redaction (spec §8, §15.3) |

## Design spec (authoritative source of truth)

> **[`docs/superpowers/specs/2026-06-16-spring-ai-alibaba-rag-design.md`](./superpowers/specs/2026-06-16-spring-ai-alibaba-rag-design.md)**
> is the design specification. The files above are the operational view
> of the spec — when they disagree, the spec wins.

## Module map

```
rag-core/         — domain model + ports (no Spring, no Redis, no LLM)
rag-redis/        — Redis Stack vector store + 3-tier cache
rag-pipeline/     — orchestrators (IngestService, QAService, ContextAssembler, Rewrite)
rag-embedding/    — Stub adapters + SiliconFlow real adapters (EmbeddingGateway / RerankService / LlmService); toggle via `rag.siliconflow.enabled` + `SILICONFLOW_API_KEY`
rag-app/          — Spring Boot wiring (HTTP, MDC, OpenAPI 3, RFC 7807 errors, e2e test)
```

## Pipeline status (live)

| Phase | Status | Commit |
|---|---|---|
| 1. Project skeleton (multi-module Maven) | ✅ shipped | `3650013` |
| 2. rag-core (domain + 17 unit tests) | ✅ shipped | `0f466cc` |
| 4-P1. Redis connection + IndexManager | ✅ shipped | `e7d82b3` |
| 4-P2. RedisVectorStore + codec | ✅ shipped | `d307078` |
| 4-P3. Three-tier cache (rewrite / embedding / answer) | ✅ shipped | `02b063b` |
| 5-P1. Document / ChunkSplitter | ✅ shipped | `31d765d` |
| 5-P2. IngestService orchestrator | ✅ shipped | `b2b6f44` |
| 5-P3-A. ContextAssembler (token budget + PII) | ✅ shipped | `632719a` |
| 5-P3-B. RuleBasedQueryRewriter | ✅ shipped | `453c005` |
| 5-P3-C. QAService (8-step chain + 7-tier degradation) | ✅ shipped | `51ede3a` |
| 5-P3-D. Operational docs (this folder) | ✅ shipped | `8bd9104` |
| 6-D1. rag-app Spring Boot skeleton + REST `POST /api/qa` + MDC + stub beans | ✅ shipped | `5c128aa` |
| 6-D2+D6. OpenAPI 3 + RFC 7807 problem+json errors | ✅ shipped | `f1b2873` |
| 6-D5. Refactor: move Embedding/Rerank/LLM stubs out of rag-app → rag-embedding | ✅ shipped | `4898b34` |
| 6-D4. Redis-backed end-to-end test + RedisAutoConfiguration | ✅ shipped | `38628cf` |
| 5-P4. SiliconFlow real adapters (`BAAI/bge-m3` 1024-dim / `BAAI/bge-reranker-v2-m3` / `Qwen/Qwen2.5-7B-Instruct`) | ✅ shipped | `72fca10` |
| 7. SiliconFlow auto-config cleanup + duplicate main class removal | ✅ shipped | `83785d1` |

**Test count**: 177 tests, all green (`mvn verify`) — 124 pipeline + 22 embedding (9 stub + 13 siliconflow unit) + 6 redis-core + 8 rag-app controller + 17 rag-core. 23 Redis Stack smoke + 1 IT + 5 SiliconFlow IT skipped when upstream unavailable (see RUNBOOK.md / `rag-embedding/.env.example`).

## SiliconFlow adapters (Phase 5-P4)

When `rag.siliconflow.enabled=true` **and** `SILICONFLOW_API_KEY` env var is set, the
real SiliconFlow adapters override the stubs. Defaults (see `rag-embedding/.env.example`):

| Port | Model | Dim / size |
|---|---|---|
| Embedding | `BAAI/bge-m3` | 1024-dim |
| Rerank | `BAAI/bge-reranker-v2-m3` | pairs with bge-m3 |
| LLM | `Qwen/Qwen2.5-7B-Instruct` | lowest-cost tier |

To enable: copy `rag-embedding/.env.example` → `rag-embedding/.env`, fill in your key,
then `set -a; source rag-embedding/.env; set +a` before `mvn spring-boot:run`.

### Verified against real SiliconFlow (2026-06-17)

Run `mvn -pl rag-embedding test -Dtest=SiliconFlowIT` with `SILICONFLOW_API_KEY`
+ `SILICONFLOW_IT=1` set. Last verification (5 tests, 4.5s):

| IT | Result |
|---|---|
| `embedding_bgeM3_returns1024dimVectors` | ✅ 1024-dim, non-zero magnitudes |
| `embedding_similarity_ordersAsExpected` | ✅ refund-doc > weather-doc by ≥ 0.05 cosine |
| `rerank_bgeReranker_reordersCorrectly` | ✅ top-1 is refund-related chunk |
| `llm_qwen7B_respondsInChinese` | ✅ Qwen2.5-7B: "Spring Boot 是一种用于简化 Spring 应用 开发的框架" |
| `embedding_badKey_401_throwsUnavailable` | ✅ 401 → EmbeddingUnavailableException (no retry) |
