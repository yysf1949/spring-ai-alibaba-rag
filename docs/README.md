# spring-ai-alibaba-rag — Documentation

| File | Audience | What it covers |
|---|---|---|
| [RUNBOOK.md](./RUNBOOK.md) | Operator | Local boot · docker-compose · smoke test · troubleshooting |
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
rag-embedding/    — DashScope embedding gateway (SPEC §13.5) — *not yet implemented*
rag-app/          — Spring Boot wiring (HTTP, config, beans) — *not yet implemented*
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
| 5-P3-D. Operational docs (this folder) | 🔜 this commit | — |
| 5-P4. DashScope: EmbeddingGateway / RerankService / LlmService | ⛔ blocked on API key | — |
| 6. rag-app (Spring Boot wiring, HTTP, observability) | ⏳ after 5-P4 | — |

**Test count**: 124 unit tests, all green (`mvn verify`). 23 redis-stack smoke tests skipped when Redis Stack is unavailable (see RUNBOOK.md).
