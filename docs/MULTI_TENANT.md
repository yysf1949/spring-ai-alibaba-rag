# Multi-tenant contract — spring-ai-alibaba-rag

> **Authoritative source**: spec §8 (which corresponds to article §15).
> This document is the **operational** view: it tells callers what they
> can and cannot do, and tells operators what to enforce.

---

## 1. The hard wall: `tenantId`

`tenantId` is the **only** field that partitions data. There is no
"superuser" or "cross-tenant admin" mode — every read, every write,
every metric, every log line is scoped to one tenant.

| Field | Required? | Where it's enforced |
|---|---|---|
| `tenantId` on every `Query` | **yes — `Query` constructor rejects blank** | `Query` record, `QAServiceImpl.retrieve` |
| `tenantId` on every `Chunk` | **yes** | `rag-redis` vector store key (`rag:index:{tenant}:{kbId}`) |
| `tenantId` on every cache key | **yes** | `rag:answer-cache:{tenant}:{queryHash}` |
| `tenantId` on every log line | **yes** | MDC entry set by `rag-app` HTTP filter |
| `tenantId` on every metric | **yes** | label, never omitted |

### What "hard wall" means in practice

- A `Query` for `tenant=A` **cannot** read chunks owned by `tenant=B`,
  even if `kbId` collides. The vector store key includes `tenantId`,
  so `FT.SEARCH` on `rag:index:A:kb-1` cannot see the chunks stored
  under `rag:index:B:kb-1`.
- The cross-tenant check happens **at the index boundary** (Redis
  Stack), not at the application boundary. The app cannot accidentally
  bypass it by mis-filtering a query.
- **There is no admin key** to force a cross-tenant read. To debug
  a cross-tenant issue, you must reproduce it in a single-tenant
  test fixture (see `rag-redis` smoke tests).

---

## 2. Knowledge base isolation: `kbId` × `kbVersion`

Inside one tenant, knowledge bases are versioned (spec §4, §8.1).

```
rag:index:{tenantId}:{kbId}-{kbVersion}-staging   ← chunks being ingested
rag:index:{tenantId}:{kbId}-{kbVersion}            ← chunks being served (post-publish)
```

| Field | When required | What happens if absent |
|---|---|---|
| `kbId` on `Query.kbVersion()` | Always | If null, `VectorStore.search` falls back to the currently-published version |
| `kbVersion` on `Query.kbVersion()` | When pinning a draft | If null, the live (published) index is used |

The **publish** operation (spec §6.1, "原子切换") atomically swaps
`staging → active` so a half-built version is never visible to query
traffic. There is no "eventual consistency" window.

---

## 3. Permission model (spec §8.2, §15.2)

Each user has a set of **permission tags** (e.g. `ROLE_USER`,
`ROLE_SUPPORT`, `DOC_FINANCE_OK`). Each chunk has a set of **required
tags**. The retrieval filter applies one of two modes:

| Mode | Behaviour | When to use |
|---|---|---|
| **AND** (default) | `user.tags ⊇ chunk.tags` — user must have **every** tag the chunk requires | Confidential content: a chunk tagged `{ROLE_SUPPORT, DOC_FINANCE_OK}` requires both |
| **OR** | `user.tags ∩ chunk.tags ≠ ∅` — user must have **at least one** | Broad-access content: a public FAQ chunk tagged `{ROLE_USER, ROLE_SUPPORT}` matches either |

The mode is set at query time via `Query.permissionMode()`. **There is
no per-chunk mode flag** — operators decide globally whether a tenant
uses AND or OR. (The mode will be per-tenant in the future, see spec
§8.2 TODO.)

### What gets filtered

The filter is applied at **vector-search time**, not after retrieval.
This means:

- An unauthorised user does **not** see the chunk at all, even in
  citation count, even in `topK=20` (the chunk simply doesn't appear
  in the candidate pool).
- The LLM never sees chunks the user cannot read.
- The `retrieved` and `reranked` lists in the `Answer` object only
  contain authorised chunks.

### What does NOT get filtered

- **The question itself**. The user's question text goes to the
  rewriter and (if fallback fires) into the LLM prompt as-is. Sensitive
  content in the question is the **caller's** responsibility (see §5).
- **The KB id**. A user with permission to read `kb-1` cannot read
  `kb-2` even if they explicitly ask for it — kbId is set by the
  application, not the user.

---

## 4. Multi-tenant request example

A legal request from `tenant=A`:

```json
{
  "tenantId": "A",
  "userId": "alice",
  "sessionId": "sess-42",
  "rawText": "运费怎么退？",
  "permissionTags": ["ROLE_USER"],
  "kbVersion": { "tenantId": "A", "kbId": "kb-1", "version": 1 },
  "permissionMode": "AND"
}
```

The pipeline:
1. Routes the embedding to the cache key `rag:embedding-cache:{textHash}`.
   The text hash is **content-only** (no tenant) — two tenants asking
   the same question share the embedding cache. **This is safe**: the
   embedding is a deterministic function of the text, not the tenant.
2. Searches `rag:index:A:kb-1-1` (active pool of version 1).
3. Filters by `chunk.permissionTags ⊆ {ROLE_USER}`.
4. Reranks, assembles, generates.
5. Caches the answer at `rag:answer-cache:A:{queryHash}`.

A cross-tenant request is structurally impossible — `tenantId` is
required, validated, and embedded in every key.

---

## 5. Sensitive information redaction (spec §15.3)

The `ContextAssembler` (P3-A) runs every chunk body through a
`SensitiveDataRedactor` **before** it appears in the LLM prompt. The
default implementation (`DefaultSensitiveDataRedactor`) is regex-based
and covers:

| PII type | Regex shape | Replacement |
|---|---|---|
| Chinese ID card (18-char, last may be `X/x`) | `(?<!\d)(?:\d{17}[0-9Xx]\|\d{15})(?!\d)` | `***ID-REDACTED***` |
| Bank card (16-19 digits) | `(?<!\d)\d{16,19}(?!\d)` | `***BANK-REDACTED***` |
| Chinese mobile (11 digits, `1[3-9]xxxxxxx`) | `(?<!\d)1[3-9]\d{9}(?!\d)` | `***PHONE-REDACTED***` |

### Why regex and not Luhn?

- **Luhn check** catches typos in fake bank numbers but is CPU-expensive
  on every chunk. For retrieval-time PII stripping, false positives
  are acceptable (over-redacting) and false negatives are the real risk.
- **For payment flows** (where you actually charge the card), use a
  dedicated payment processor with its own PCI-DSS scope, not the
  RAG redaction layer.

### Why the order matters

`ID_CARD` runs first because its 18-char shape is a strict superset of
any bank-card pattern that accepts 18 digits. If `BANK_CARD` ran first,
it would steal the 18 digits of an ID card and the trailing `X`
would be left dangling. See the comment in
`DefaultSensitiveDataRedactor` for the full ordering rule.

### How to add a new PII pattern

See RUNBOOK §6.4.

### What redaction does NOT do

- **The question text**. The user's own text is **not** redacted before
  sending to the LLM. This is deliberate: the LLM needs to see what
  the user actually said. If your domain requires user-side redaction,
  do it at the API gateway before the request enters the RAG pipeline.
- **The metadata fields** (`title`, `sectionPath`, `sourceUri`).
  These are intentionally **never** redacted — they're the citation
  breadcrumbs. If your titles contain PII (e.g. "Alice's KYC form"),
  sanitise at ingest time, not at retrieval time.

---

## 6. Tenant onboarding checklist

For operators adding a new tenant:

- [ ] Generate `tenantId` (UUID or slug; both work). The tenant id is
      the primary key in every index — pick a stable, human-readable
      one (e.g. `acme-prod`, not `t_a8f3c1`).
- [ ] Decide the tenant's default `kbId` and `kbVersion` (usually
      `kbVersion=1` after the first publish).
- [ ] Decide the tenant's `permissionMode` (AND or OR; default AND).
- [ ] Configure the tenant's permission tags (the **user** tags you'll
      be issuing at login time).
- [ ] Run the first ingest: POST a document batch to the ingest
      endpoint (future `rag-app` HTTP layer; for now use
      `IngestService` directly from a Java main).
- [ ] Call `IngestService.publish(tenantId, kbId, version)` to flip
      from staging to active.
- [ ] Smoke-test with a representative `Query` and a non-zero
      `permissionTags` set; assert the `Answer` contains chunks with
      matching tags.

---

## 7. Off-boarding a tenant

To remove all data for a tenant:

```java
// Run as a one-off main, not as part of the request path.
vectorStore.deleteByIds(tenantId, kbId, version, List.of("*"));  // wildcard → all
answerCache.invalidateTenant(tenantId);                          // clears the whole answer cache
// Embedding cache is content-keyed (not tenant-keyed) — it will
// evict naturally via LRU. Force-eviction requires SCAN+DEL.
```

> **Audit trail**: every tenant write to the vector store also writes
> an audit entry (spec §16.2 — MDC `tenant` field). Off-boarding does
> **not** delete audit logs; that's the audit retention policy's job.

---

## 8. Anti-patterns to refuse

These request shapes are **structurally** rejected. If a caller is
asking for one of these, they have a design bug — push back rather
than accommodate.

| Anti-pattern | Why it's wrong | What to do instead |
|---|---|---|
| `tenantId=null` | `Query` constructor throws | Set `tenantId` from the authenticated principal |
| `permissionTags=null` | Defaults to `Set.of()` — user has zero permissions, sees zero chunks | Populate from the user's role mapping |
| Asking to bypass permissions by setting `permissionMode=OR` with empty tags | The filter degenerates to "no filter" — exposes every chunk | Refuse; an empty `permissionTags` should always mean "no access" |
| Sending `tenantId=A` in the body but the API gateway is authenticated as `tenant=B` | Trust boundary violation | The HTTP layer must overwrite `tenantId` with the gateway-resolved value, never trust the body |
| Asking for `kbVersion=-1` "to see everything" | `-1` is reserved for "use currently-published"; never a wildcard | Pin a real version, or use null |

---

## 9. Reading order for new engineers

1. Spec §4 (data model) — understand `Query`, `Chunk`, `KbVersion`
2. Spec §8 (this document, the spec version)
3. `Query.java` and `Chunk.java` in `rag-core/model/`
4. `QAServiceImplTest` in `rag-pipeline/qa/` — see the legal / illegal
   request shapes in action
5. `DefaultSensitiveDataRedactorTest` — see what gets caught, what doesn't
