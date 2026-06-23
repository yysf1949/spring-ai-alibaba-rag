# Phase 18 P2 — KB 版本管理 API (port + 4 store + tool + controller)

> 仓库: yysf1949/spring-ai-alibaba-rag (feature/agent-action-layer)
> HEAD ship: `a990dec` (本地 == 远端 MATCH)
> 配套 evolution.md: `docs/evolution.md` Phase 18 P2 段
> Plan: `docs/superpowers/plans/2026-06-18-phase-18-p2-kb-version-api.md`

---

## 一句话总结

抽出 `KbVersionService` port, 4 backend (H2/MySQL/Jdbc/Redis) 实现, 加 Tool + REST Controller, **不动 Phase 7-9 ship 的 VectorStore**, 让 Agent 和前端能列出历史版本 / 显式 rollback / 查 active.

---

## 业务背景

Phase 7-9 ship 后 rag-pipeline 的 `VectorStore` 只接 `kb_id`, 每次 publish 写覆盖索引 (单 `publishPointer` 切到新版本). 用户缺 4 个能力:

1. 列出该 KB 下历史 publish 过哪些版本
2. 显式 rollback 到历史版本
3. 知道当前 active version 是哪个
4. 通过 tool / controller 调用

P2 解决这 4 个.

---

## 关键设计决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| E1 | 范围 | 整 KB 粒度版本 | spec §20 + 简化; 文档粒度可推 Phase 19 |
| F1 | versionId | long 自增 (时间戳 + seq) | 跨 backend 唯一单调; UUID 人读不出顺序 |
| G1 | publish 模式 | 显式 publish | rollback 必须有显式 publish 入口 |
| H1 | 状态模型 | 1 active + N historical | 满足 rollback + 历史列表 |
| M1 | Port 位置 | rag-core | 跟 VectorStore / EmbeddingGateway 同 module |
| M2 | Store 分布 | H2/MySQL/Jdbc → rag-pipeline; Redis → rag-redis | 按 backend 所在模块 |
| M3 | Jdbc 抽象 | `JdbcKbVersionService` abstract base | H2/MySQL 只重写 DDL |
| R1 | Redis 结构 | hash meta + 复用 publishPointer + ZSET versions | meta 详情查 hash; active 走 publishPointer |
| S1 | upsert | SELECT-then-INSERT/UPDATE | 不用方言 MERGE/ON CONFLICT |
| T1 | KbSearchTool | 永远传 `kbVersion=-1` | 解析下沉到 RetrievalAdapter |
| B1 | 兼容性 | 新增 nullable ctor; 旧 2-arg 保留 | 向后兼容 Phase 17 caller |
| V1 | 解析条件 | `<0` → resolveVersion; `>=0` → 透传 | 用户给具体值就该尊重 |

---

## ship 清单 (T2.1 → T2.6, 2 commit)

| T# | 内容 | 行 | 文件 |
|---|---|---|---|
| T2.1 | rag-core port + record + exception | +150 | 3 |
| T2.2 | 4 store 实现 | +650 | 4 |
| T2.3 | RetrievalAdapter 注入 | +60 | 1 |
| T2.4 | KbVersionTool (L2_WRITE) + Request/Response/Action | +260 | 4 |
| T2.5 | KbVersionController REST | +150 | 1 |
| T2.6 | 50 测试 (H2 14 + Redis 17 + tool 10 + ctrl 5 + adapter 4) | +1100 | 6 |
| **总计** | | **+2370 行 / 19 文件** | |

---

## API

### REST

```
GET  /api/kb/versions?tenantId=t1&kbId=kb-product
POST /api/kb/versions/switch   {tenantId, kbId, versionId}
POST /api/kb/versions/publish  {tenantId, kbId, versionId, sourceLabel?}
```

### Tool (供 Agent 调用, L2_WRITE 权限)

```java
kb_version_tool(action="LIST",        tenantId="t1", kbId="kb-product")
kb_version_tool(action="PUBLISH",     tenantId="t1", kbId="kb-product", versionId=1749999999001L, sourceLabel="docs-v2.zip")
kb_version_tool(action="SWITCH",      tenantId="t1", kbId="kb-product", versionId=1749999998001L)  // rollback
kb_version_tool(action="GET_ACTIVE",  tenantId="t1", kbId="kb-product")
```

---

## 关键坑 (后续 Phase 必看)

1. **`git add -A` 是陷阱** — 误把 `.env.example` / `openjdk-21-jdk.deb` (670KB) / phase plan MD 都进 commit. 修复: `git reset --soft HEAD~1 && rm <bad> && git reset HEAD` + 重 commit. **永远明确 add 文件名, 不用 `-A`**.
2. **`UnifiedJedis.hset` 多态** — 5.2.0 接受 `Map<String,String>` 或 `(String, String, String)` 单字段; 多字段用 `Map`, 单字段用 3-arg form.
3. **abstract JdbcKbVersionService 不要持有 datasource** — 子类 H2/MySQL 在 ctor 自己拿; parent 只暴露 hook `protected abstract String upsertSql()`.
4. **KbVersionNotFoundException 不要 500** — agent 调 `GET_ACTIVE` 而 KB 没 active 版本应该返 200 + 空 activeVersion, 不是 500 (LLM 自己处理"无 active").
5. **真 DeepSeek E2E vs adapter E2E 边界** — version 解析路径涉及 LLM 调用的是 controller E2E (用 `@MockBean RetrievalAdapter`); 跨 backend 实际 SQL/Redis 是 H2/Redis service 单元测试. 真 LLM E2E 仍只有 chat-memory 那 1 个 (P1 写).

---

## 测试基线

| 模块 | 测试数 | 增量 (P1 → P2) |
|---|---|---|
| rag-core | 18 | - |
| rag-pipeline | **176** | +18 (H2 service 14 + adapter E2E 4) |
| rag-redis | **175** | +17 (Redis mock) |
| rag-agent | **287** | +15 (tool 10 + controller 5) |
| **总计** | **656** | **+50 新, 0 fail, 0 skip** |

---

## Phase 19 待做

- 文档粒度版本 (E2)
- Partial re-index
- 真 LLM E2E 走 controller (P2 ship 时用 @MockBean 跳过)

---

## Ship 状态

- P0 ship: HEAD `eac0fe8`
- P1 ship: HEAD `6ebd1ba`
- **P2 ship: HEAD `a990dec`**
- **远端 MATCH** ✓
