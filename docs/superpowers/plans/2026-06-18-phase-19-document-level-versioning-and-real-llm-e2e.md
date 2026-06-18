# Phase 19 — 文档级版本管理 + 真 LLM E2E (2026-06-18)

> Branch: `feature/agent-action-layer`
> 起点 HEAD: `97218eb` (Phase 18 P2 ship, MATCH)
> 配套 evolution.md: `docs/evolution.md` Phase 19 段
> 目标: 在 Phase 18 P2 (整 KB 级) 之上叠加文档级版本, 真 LLM 走 publish → search → rollback → search 闭环

---

## 1. 业务背景

Phase 18 P2 ship 后, KB 版本粒度是**整 KB**: 一次 publish 整个 KB 切到新版本. 用户真实诉求更细:

1. 同一 KB 内"产品手册 v1.pdf"v2 上线后, 老引用(已嵌入 chat 的 citation)不应瞬时全失效 (P2 ship 后会)
2. 想看"某 doc 的历史版本" (审计/回滚到具体 doc)
3. partial re-index (只重建一个 doc, 不重建整个 KB)

Phase 19 解决 1+2+3: 文档级版本层 + 真 LLM 走全流程 E2E 验证.

---

## 2. 关键决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| E2 | 版本粒度 | 文档组级 (chunk 集合 + `doc_id` tag) | spec §20 phase 19 暗示 + Chunk 已有 metadata, 不必拆数据模型 |
| F2 | versionId 命名 | 沿用 P2 long 自增 | 一致性 + 跨 backend 单调 |
| H2 | 真 E2E 范围 | 1 个 (publish doc → LLM 查 → rollback → LLM 再查, 验证 active 切换) | 沿用 P1 ChatMemory E2E 风格 (单 E2E 覆盖全链路) |
| X1 | Redis 存储 | 新 ZSET-per-doc (`rag:kb-doc-versions:{tenant}:{kb}:{docId}`) | 数量可控 + 按 doc 查询历史 |
| J1 | 兼容性 | P2 port 不改, 加新 port `DocumentVersionService` (按 doc 维度) | 不污染 Phase 18 ship |
| J2 | 检索路径 | `RetrievalAdapter` 接受 `Map<docId, versionId>` override, 无 override → 走 P2 整 KB 路径 | 显式 override > 隐式 |
| K1 | 真 LLM 路径 | 复用 P1 `DeepSeekConfig` (env: `DEEPSEEK_API_KEY`), 不新增 provider | 一致性 |
| K2 | E2E 跳过条件 | `DEEPSEEK_API_KEY` 为空 → `@EnabledIfEnvironmentVariable` skip | CI 友好 |

---

## 3. 设计决策表

### 3.1 数据模型

```java
// rag-core 新增
public record DocumentVersionMeta(
    String tenantId, String kbId, String docId, long versionId,
    DocumentVersionStatus status,  // ACTIVE / HISTORICAL / DRAFT
    Instant createdAt, Instant publishedAt,
    int chunkCount, String sourceLabel
) {}

public interface DocumentVersionService {
    // 类似 P2 KbVersionService, 但加 docId 维度
    List<DocumentVersionMeta> listVersions(String tenantId, String kbId, String docId);
    Optional<Long> getActiveVersion(String tenantId, String kbId, String docId);
    long resolveVersion(String tenantId, String kbId, String docId, long requestedVersion);
    DocumentVersionMeta publish(String tenantId, String kbId, String docId, long versionId, String sourceLabel);
    DocumentVersionMeta rollback(String tenantId, String kbId, String docId, long targetVersion);
}
```

### 3.2 检索路径

```java
// RetrievalAdapter 新增 ctor 接受 DocumentVersionService + override map
public List<RetrievedChunk> search(SearchQuery query, Map<String, Long> docVersionOverrides);
```

**优先级**: override (per-doc) > DocumentVersionService resolve > P2 KbVersionService (per-KB) > 0 (legacy)

### 3.3 后端存储

| Backend | 实现位置 | 数据结构 |
|---|---|---|
| H2 | rag-pipeline (新) | `kb_doc_version` 表: (tenant, kb, doc, version, status, ...) |
| MySQL | rag-pipeline (新) | 同 H2 + InnoDB utf8mb4 |
| Jdbc | rag-pipeline (新, abstract) | 跨方言 portable SELECT-then-INSERT/UPDATE |
| Redis | rag-redis (新) | ZSET `rag:kb-doc-versions:{t}:{kb}:{doc}` (score=versionId) + hash `rag:kb-doc-version-meta:{t}:{kb}:{doc}:{ver}` + publishPointer key |

---

## 4. ship 清单 (T1 → T6)

| T# | 内容 | 行 | 文件 |
|---|---|---|---|
| T1 | `rag-core` port + record + exception | +200 | 3 |
| T2 | 4 store 实现 (H2/MySQL/Jdbc/Redis) | +700 | 4 |
| T3 | `RetrievalAdapter` 新 ctor + 优先级链 (override > DocService > KbService > 0) | +100 | 1 |
| T4 | `DocumentVersionTool` (L2_WRITE, 4 actions: LIST/SWITCH/PUBLISH/GET_ACTIVE) | +280 | 4 |
| T5 | `DocumentVersionController` REST: `GET /api/kb/docs/versions`, `POST /switch`, `POST /publish` | +180 | 1 |
| T6 | 测试: H2 14 + Redis 17 + tool 10 + ctrl 5 + adapter 5 + **真 DeepSeek E2E 1** = **52 新测试** | +1300 | 7 |
| T7 | evolution.md P19 段 + Obsidian 归档 | +120 | 2 |
| **总计** | | **+2880 行 / 22 文件** | |

---

## 5. 真 LLM E2E 设计 (T6 关键)

**目的**: 验证 publish → search 看到 v2 → rollback → search 看到 v1 全链路真实 LLM + 真 VectorStore.

```
test: rag-agent/.../DocumentVersionRollbackE2ETest
  @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
  @SpringBootTest

场景:
  1. setup: H2 store + 2 文档 (doc-A="退款规则", doc-B="配送规则") 各 5 chunks
  2. publish doc-A v1 → active = v1
  3. user prompt: "退款规则?" → DeepSeek 调 kb_search → 返 doc-A v1 chunk
  4. 校验 LLM 答案含 doc-A v1 内容 ("7 天无理由")
  5. publish doc-A v2 (内容: "15 天无理由") → active = v2
  6. user prompt: "退款规则?" → DeepSeek 调 kb_search → 返 doc-A v2 chunk
  7. 校验 LLM 答案含 doc-A v2 内容 ("15 天")
  8. rollback doc-A v1 → active = v1
  9. user prompt: "退款规则?" → DeepSeek 调 kb_search → 返 doc-A v1 chunk
  10. 校验 LLM 答案含 doc-A v1 内容 ("7 天")

依赖: DEEPSEEK_API_KEY env, 走真实 DeepSeek ChatCompletion
时间预算: ~20-30s (3 次 LLM 调用)
```

---

## 6. 关键坑 (已知 / 预防)

1. **`git add -A` 禁** (P2 已踩) — 走 explicit paths + 三确认
2. **E2E 真凶 = DeepSeek 限流** — 3 次调用要 sleep 间隔 ≥1s, 避免 429
3. **Abstract JdbcDocumentVersionService** — 不要持 datasource, parent 只暴露 `upsertSql()` hook (P2 教训)
4. **`@EnabledIfEnvironmentVariable` skip 后 surefire 报告 PASS 但 skip** — CI 必须显示 skip 数, 不算 fail
5. **ZSET-per-doc 数据爆炸** — 限制 max 100 versions/doc (LRU), 老的 archive 到冷表
6. **优先级链 test 用 matrix** — 4 个 source (override/DocService/KbService/0) × 3 backend (H2/MySQL/Redis) = 12 组合, 至少测 4 个代表 (H2-override/H2-KbService/Redis-DocService/Redis-0)

---

## 7. 验证 ritual (每 T 后必走)

```bash
mvn -pl <module> -am test 2>&1 | tail -30  # exit 0 + 0 fail
git add <explicit paths>
git status -s  # 必只列本 T 文件
git commit -m "feat/topic(scope): ..."
git push origin feature/agent-action-layer
git ls-remote origin feature/agent-action-layer  # 必 MATCH
```

---

## 8. 时间估算

- T1-T3: 30 分钟 (P2 模式 copy)
- T4-T5: 25 分钟 (P2 模式 copy)
- T6 单元测试: 20 分钟 (P2 模式 copy)
- T6 真 LLM E2E: 15 分钟 (手写 3 轮 prompt + assertion)
- T7 docs: 10 分钟 (P2 模式 copy)
- **总计: ~100 分钟, 期望 4 commit (T1-T3/T4-T5/T6/T7)**
