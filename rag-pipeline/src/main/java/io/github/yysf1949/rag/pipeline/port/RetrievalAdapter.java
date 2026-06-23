package io.github.yysf1949.rag.pipeline.port;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.KbVersionService;
import io.github.yysf1949.rag.core.port.RetrievalPort;
import io.github.yysf1949.rag.core.port.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RetrievalPort} 的 rag-pipeline 实现 — Phase 17 T2 + Phase 18 P2。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>调 {@link KbVersionService#resolveVersion} 解析 {@code kbVersion} (Phase 18 P2 新)
 *       — 把 {@code -1} 翻译成"当前 active version", 校验指定版本存在</li>
 *   <li>调 {@link EmbeddingGateway#embedBatch(List)} 把 query 转向量（用单元素 list）</li>
 *   <li>调 {@link VectorStore#search} 取 topK 候选 chunk（多租户/kb/kbVersion/permissionTags 硬墙）</li>
 *   <li>从 query vector 与 chunk.embedding 重算 cosine similarity，归一化到 0-1 作为 {@code score}</li>
 *   <li>把 {@link Chunk} 的内部字段映射成对外的 {@link RetrievedChunk}（text/kbId/kbVersion/metadata）</li>
 * </ol>
 *
 * <h2>score 计算说明</h2>
 * <p>{@link VectorStore#search} Port 签名只返 {@code List<Chunk>}（不暴露 score，避免改既有契约）；
 * 本 adapter 用 query vector 和 chunk.embedding 重算 cosine，再按
 * {@code (1 + cosine) / 2} 映射到 [0, 1]，保证 0=无关 / 1=完全相同。</p>
 *
 * <h2>kbVersion 语义 (Phase 18 P2 更新)</h2>
 * <p>Phase 17: {@code 0} 表示"用最新已发布版本"，由 {@link VectorStore} 实现内部解析。</p>
 * <p>Phase 18 P2: 把 active version 解析移到 {@link KbVersionService} Port 层, 跨 backend 一致
 * (H2/MySQL/JDBC 用 SQL 表, Redis 复用 publishPointerKey + 元数据 hash).</p>
 * <p>KbSearchTool 在传入前会把 {@code -1} 转成 {@code -1L}, adapter 内调 {@code resolveVersion}
 * 翻译成具体 version id (≥0) 再透传给 {@link VectorStore#search}.</p>
 *
 * <h2>不做</h2>
 * <ul>
 *   <li>不做 rewrite/rerank/cache/fallback — 这些是 QAService 8 步链的事，跟 agent 工具层解耦</li>
 *   <li>不动 {@code Chunk} 模型（保留 Phase 7-9 已 ship 契约）</li>
 *   <li>不持久化 cache — 走 VectorStore 自带的索引缓存</li>
 * </ul>
 */
@Component
@ConditionalOnBean({VectorStore.class, EmbeddingGateway.class})
public class RetrievalAdapter implements RetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAdapter.class);

    private final VectorStore vectorStore;
    private final EmbeddingGateway embeddingGateway;
    private final KbVersionService kbVersionService;
    private final DocumentVersionService documentVersionService;

    /**
     * Phase 19 constructor — wires in {@link DocumentVersionService} on top of
     * the Phase 18 P2 {@link KbVersionService} injection.
     *
     * <p>Both services are allowed to be {@code null} for backward compatibility
     * with tests that hand-build a {@code RetrievalAdapter} without a full
     * Spring context. When {@code null}, the adapter behaves exactly as it did
     * pre-P2: pass {@code requested} through unchanged and rely on each
     * backend's internal {@code 0 = active} convention.</p>
     */
    @Autowired
    public RetrievalAdapter(VectorStore vectorStore,
                            EmbeddingGateway embeddingGateway,
                            @Autowired(required = false) KbVersionService kbVersionService,
                            @Autowired(required = false) DocumentVersionService documentVersionService) {
        this.vectorStore = vectorStore;
        this.embeddingGateway = embeddingGateway;
        this.kbVersionService = kbVersionService;
        this.documentVersionService = documentVersionService;
    }

    /**
     * Phase 18 P2 constructor — kept for backward compatibility.
     */
    public RetrievalAdapter(VectorStore vectorStore,
                            EmbeddingGateway embeddingGateway,
                            @Autowired(required = false) KbVersionService kbVersionService) {
        this(vectorStore, embeddingGateway, kbVersionService, null);
    }

    /**
     * Legacy constructor — kept so unit tests that mock just (VectorStore, EmbeddingGateway) keep compiling.
     */
    public RetrievalAdapter(VectorStore vectorStore, EmbeddingGateway embeddingGateway) {
        this(vectorStore, embeddingGateway, null, null);
    }

    @Override
    public List<RetrievedChunk> search(
            String tenantId,
            String kbId,
            long kbVersion,
            String query,
            int topK,
            List<String> userPermissionTags
    ) {
        return search(tenantId, kbId, kbVersion, query, topK, userPermissionTags, null);
    }

    /**
     * Phase 19: extended search overload with per-document version override.
     *
     * <p>Resolution priority for which version of a document to retrieve:
     * <ol>
     *   <li><b>Caller override</b> — entries in {@code docVersionOverrides}
     *       keyed by {@code documentId}. Highest priority; lets callers pin a
     *       specific doc to a specific version (e.g. rollback one doc without
     *       touching the rest of the KB).</li>
     *   <li><b>{@link DocumentVersionService}</b> — when a service is wired
     *       in and the chunk's {@code documentId} is non-null, resolve via
     *       {@link DocumentVersionService#resolveVersion} (handles
     *       {@code -1} → active).</li>
     *   <li><b>{@link KbVersionService}</b> — for {@code kbVersion < 0}, resolve
     *       via {@link KbVersionService#resolveVersion} (Phase 18 P2 path).</li>
     *   <li><b>Pass-through</b> — for {@code kbVersion >= 0} with no override
     *       and no document resolution, use {@code kbVersion} as-is.</li>
     * </ol>
     *
     * @param docVersionOverrides per-document version override map; may be
     *                            {@code null} or empty. Keys are {@code docId}.
     */
    public List<RetrievedChunk> search(
            String tenantId,
            String kbId,
            long kbVersion,
            String query,
            int topK,
            List<String> userPermissionTags,
            Map<String, Long> docVersionOverrides
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK <= 0) {
            topK = 5;
        }
        List<String> tags = userPermissionTags == null ? List.of() : userPermissionTags;
        Map<String, Long> overrides = docVersionOverrides == null ? Map.of() : docVersionOverrides;

        // Phase 18 P2: resolve kbVersion via KbVersionService when available.
        // This gives us cross-backend consistent semantics for "use the active
        // version" instead of relying on each backend's internal convention.
        long effectiveKbVersion = kbVersion;
        if (kbVersionService != null && kbVersion < 0) {
            try {
                effectiveKbVersion = kbVersionService.resolveVersion(tenantId, kbId, kbVersion);
                log.debug("RetrievalAdapter: kbVersion {} -> effective {}", kbVersion, effectiveKbVersion);
            } catch (KbVersionNotFoundException ex) {
                // No active version → return empty result (consistent with
                // KbNotFoundException semantics — caller will surface 200 + empty).
                log.debug("RetrievalAdapter: no active version for tenant={} kb={}",
                        tenantId, kbId);
                return List.of();
            }
        }

        // 1. Embed query (use batch API, single element)
        List<float[]> vectors = embeddingGateway.embedBatch(List.of(query));
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
            throw new IllegalStateException(
                    "EmbeddingGateway returned empty vector for query (tenantId=" + tenantId
                            + ", kbId=" + kbId + ")");
        }
        float[] queryVector = vectors.get(0);
        if (queryVector.length != embeddingGateway.dimension()) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: gateway=" + embeddingGateway.dimension()
                            + ", got=" + queryVector.length);
        }

        // 2. Vector search (hard wall: tenant/kb/kbVersion/permissionTags)
        List<Chunk> raw = vectorStore.search(
                queryVector,
                tenantId,
                kbId,
                effectiveKbVersion,
                tags,
                PermissionMode.AND,
                topK);

        if (raw == null || raw.isEmpty()) {
            log.debug("RetrievalAdapter.search: no chunks (tenantId={}, kbId={}, kbVersion={})",
                    tenantId, kbId, effectiveKbVersion);
            return List.of();
        }

        // 3. Map Chunk -> RetrievedChunk + recompute cosine score
        // Phase 19: if DocumentVersionService is wired, prefer its resolved
        // version for the chunk's docId (overrides win over service, service
        // wins over kbVersion).
        return raw.stream()
                .map(c -> toRetrievedChunk(c, queryVector))
                .filter(rc -> keepByDocVersion(rc, overrides, tenantId, kbId))
                .toList();
    }

    /**
     * Phase 19: filter / down-rank retrieved chunks based on per-doc version
     * override or {@link DocumentVersionService} resolution.
     *
     * <p>For now we <strong>drop</strong> chunks whose resolved doc-version
     * doesn't match what the caller asked for (override map) or what the
     * service resolved. This keeps the contract simple: callers who pass
     * overrides get exactly those doc versions. Callers who don't, get what
     * the service says is active. In the future this could be a soft
     * re-rank, but a hard filter matches the P2 semantic ("list active
     * versions, rollback active version").</p>
     */
    private boolean keepByDocVersion(RetrievedChunk rc,
                                     Map<String, Long> overrides,
                                     String tenantId,
                                     String kbId) {
        String docId = rc.metadata() == null ? null : rc.metadata().get("documentId");
        if (docId == null || docId.isBlank()) {
            // No documentId on chunk — cannot apply per-doc version filter;
            // keep it (Phase 17/18 behavior).
            return true;
        }
        // Priority 1: caller override.
        Long overrideVersion = overrides.get(docId);
        if (overrideVersion != null) {
            long chunkDocVersion = parseLong(rc.metadata().get("documentVersion"), 0L);
            return chunkDocVersion == overrideVersion;
        }
        // Priority 2: DocumentVersionService (only when no explicit
        // kbVersion override was supplied — i.e. caller asked for "active").
        if (documentVersionService != null) {
            try {
                long activeDocVersion = documentVersionService.resolveVersion(tenantId, kbId, docId, -1L);
                long chunkDocVersion = parseLong(rc.metadata().get("documentVersion"), 0L);
                return chunkDocVersion == activeDocVersion;
            } catch (DocumentVersionNotFoundException ex) {
                // No active version for this doc → drop it.
                log.debug("RetrievalAdapter: no active doc version for tenant={} kb={} doc={}",
                        tenantId, kbId, docId);
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private RetrievedChunk toRetrievedChunk(Chunk c, float[] queryVector) {
        double score = cosineToNormalizedScore(queryVector, c.embedding());
        Map<String, String> metadata = new HashMap<>();
        if (c.title() != null) metadata.put("title", c.title());
        if (c.sectionPath() != null) metadata.put("sectionPath", c.sectionPath());
        if (c.sourceUri() != null) metadata.put("sourceUri", c.sourceUri());
        if (c.documentVersion() != null) metadata.put("documentVersion", c.documentVersion());
        if (c.documentId() != null) metadata.put("documentId", c.documentId());

        return new RetrievedChunk(
                c.chunkId(),
                c.content(),
                score,
                c.kbId(),
                parseKbVersion(c),
                metadata);
    }

    /**
     * Cosine similarity -> [0, 1] 归一化（0=正交/无关, 1=完全相同）。
     * 用 {@code (1 + cosine) / 2} 把 [-1, 1] 映射到 [0, 1]。
     */
    static double cosineToNormalizedScore(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        double cosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        // NaN guard
        if (Double.isNaN(cosine) || Double.isInfinite(cosine)) {
            return 0.0;
        }
        // Clip to [-1, 1] then map to [0, 1]
        double clipped = Math.max(-1.0, Math.min(1.0, cosine));
        return (1.0 + clipped) / 2.0;
    }

    /**
     * Phase 7 Chunk 模型没存 kbVersion 字段（kbVersion 是 VectorStore.search 入参，
     * 不存到 Chunk 里）。为了 RetrievedChunk 携带 kbVersion 给 LLM 看，从
     * {@code metadata.documentVersion} 解析（rag-redis 写入时会存），fallback 0。
     */
    private long parseKbVersion(Chunk c) {
        String v = c.documentVersion();
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            // documentVersion 也可能是 semver 字符串；这里只接纯数字，Phase 18 再加 KB version API
            return 0L;
        }
    }
}