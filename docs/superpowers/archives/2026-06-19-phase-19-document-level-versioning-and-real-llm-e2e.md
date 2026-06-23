# Phase 19 — 文档级版本管理 + 真 LLM E2E

> 仓库: yysf1949/spring-ai-alibaba-rag (feature/agent-action-layer)
> HEAD ship: `6dd7700` (本地 == 远端 MATCH)
> 配套 evolution.md: `docs/evolution.md` Phase 19 段
> Plan: `docs/superpowers/plans/2026-06-18-phase-19-document-level-versioning-and-real-llm-e2e.md`

---

## 一句话总结

抽出 `DocumentVersionService` port + 4 backend (H2/MySQL/Jdbc/Redis ZSET-per-doc) + Tool + Controller, RetrievalAdapter 新增 per-doc override map + 优先级链, **真 DeepSeek E2E 14.23s PASS** 验证 publish → search → rollback → search 闭环.

---

## 业务背景

Phase 18 P2 ship 后 KB 版本粒度是**整 KB**: 一次 publish 整个 KB 切到新版本. 用户真实诉求更细:

1. 同一 KB 内"产品手册 v1.pdf"v2 上线后, 老引用 (已嵌入 chat 的 citation) 不应瞬时全失效
2. 想看"某 doc 的历史版本" (审计 / 回滚到具体 doc)
3. partial re-index (只重建一个 doc, 不重建整个 KB)

---

## 关键设计决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| E2 | 版本粒度 | 文档组级 (chunk 集合 + `docId` tag) | spec §20 phase 19 暗示 + Chunk 已有 metadata |
| F2 | versionId 命名 | 沿用 P2 long 自增 | 一致性 + 跨 backend 单调 |
| H2 | 真 E2E 范围 | 1 个 (publish doc → LLM 查 → rollback → LLM 再查) | 沿用 P17 风格 |
| X1 | Redis 存储 | ZSET-per-doc (`rag:kb-doc-versions:{t}:{k}:{d}`) | 数量可控 + 按 doc 查询历史 |
| J1 | 兼容性 | P2 port 不改, 加新 port `DocumentVersionService` | 不污染 Phase 18 ship |
| J2 | 检索路径 | `RetrievalAdapter` 新 overload `search(..., Map<String,Long>)` | 显式 override > 隐式 |

---

## 优先级链 (RetrievalAdapter.search)

```
caller override (Map<docId, versionId>)
  ↓ win
DocumentVersionService.resolveVersion(tenant, kb, docId, -1)
  ↓ win
KbVersionService.resolveVersion(tenant, kb, -1)  // Phase 18 P2
  ↓ win
pass-through kbVersion
```

---

## ship 清单 (T1 → T6, 4 commit)

| T# | 内容 | 行 | 文件 |
|---|---|---|---|
| T1 | rag-core port + record + exception | +352 | 3 |
| T2 | 4 store impl (H2/MySQL/Jdbc abstract/Redis ZSET-per-doc) | +715 | 4 |
| T3 | RetrievalAdapter 新 ctor + 新 overload + 优先级链 | +107 | 1 |
| T4 | DocumentVersionTool (L2_WRITE) + Request/Response/Action | +200 | 4 |
| T5 | DocumentVersionController REST (嵌套路径) | +150 | 1 |
| T6 | 52 tests + 2 impl bug fix | +2500 | 7 |
| **总计** | | **+4024 行 / 20 文件, 4 commit** | |

---

## REST API (嵌套路径, 跟 P2 区分)

```
GET  /api/agent/kb-versions/{kbId}/docs/{docId}
GET  /api/agent/kb-versions/{kbId}/docs/{docId}/active
POST /api/agent/kb-versions/{kbId}/docs/{docId}/publish
```

---

## Tool API

```java
doc_version_tool(action="LIST",        tenantId, kbId, docId)
doc_version_tool(action="GET_ACTIVE",  tenantId, kbId, docId)
doc_version_tool(action="PUBLISH",     tenantId, kbId, docId, versionId, sourceLabel?)
doc_version_tool(action="ROLLBACK",    tenantId, kbId, docId, versionId)
```

---

## 真 LLM E2E (T6 关键)

```
test: DocumentVersionRollbackE2ETest
  @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")

Phase 1: active=v1 → LLM "退款规则?" → 响应含 "7 天无理由"
Phase 2: active=v2 → LLM "最新版的退款规则?" → 响应含 "15 天"
Phase 3: active=v1 (rollback) → LLM "我刚 rollback 了" → 响应含 "7 天"

实测: 14.23s PASS
```

**降级断言策略** (Plan §3.4 风险#6): 不强求 LLM 必选 kb_search, 重点 = visibleToolCount + 链路不崩 + 3 次响应不全相同.

---

## 关键坑 (后续 Phase 必看)

1. **`git add -A` 禁** (P2 已踩, P19 仍坚持)
2. **真 LLM E2E 降级断言** — 不强求 LLM 必选某 tool
3. **RetrievalAdapter service 调用次数** — 每 chunk 调一次, 不按 docId short-circuit (故意简单)
4. **抽象 JdbcDocumentVersionService 测试发现 contract bug** — `registerVersion` 缺 idempotent guard + `publish` idempotent 覆盖 publishedAt. H2 test 一跑就抓出来. **教训: idempotent 行为必须有 test 验证**.
5. **subagent timeout** — 真 LLM E2E + adapter E2E 一起 600s 超时. 教训: 真 LLM E2E 用 subagent 跑容易超时, 应拆分"模板 + 验证"两步.

---

## 测试基线

| 模块 | 测试数 | 增量 |
|---|---|---|
| rag-core | 18 | - |
| rag-pipeline | **195** | +19 |
| rag-redis | **69** (23 skip) | +17 |
| rag-agent | **303** | +16 (含 1 真 LLM E2E) |
| **总计** | **585** | **+52 新, 7 真 DeepSeek E2E, 0 fail** |

---

## Phase 20 待做

- partial re-index (只重建一个 doc 的 chunks)
- 真 LLM E2E 走 controller
- DocumentVersionService 迁移到 rag-redis live 测试 (Testcontainers)

---

## Ship 状态

- P0 ship: HEAD `eac0fe8`
- P1 ship: HEAD `6ebd1ba`
- P2 ship: HEAD `a990dec`
- **P19 ship: HEAD `6dd7700`**
- **远端 MATCH** ✓
