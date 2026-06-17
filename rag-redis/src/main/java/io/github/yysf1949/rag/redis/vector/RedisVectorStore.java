package io.github.yysf1949.rag.redis.vector;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.KbNotFoundException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTSearchParams;
import redis.clients.jedis.search.SearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Stack / RediSearch backed implementation of {@link VectorStore}.
 *
 * <p>Storage model (spec §5.1 + §5.2):
 * <ul>
 *   <li>Each chunk is a Redis HASH at {@code rag:chunk:{tenant}:{chunkId}}.</li>
 *   <li>RediSearch index {@code rag:index:{tenant}:{kbVersion}} is keyed by
 *       HASH prefix {@code rag:chunk:{tenant}:*}.</li>
 *   <li>STAGING chunks are written to a sibling index
 *       {@code rag:index:{tenant}:{kbVersion}-staging}; {@link #publish(String, String, long)}
 *       atomically swaps the {@code rag:active:{tenant}:{kbId}} alias from the
 *       previous active index to the new one.</li>
 *   <li>Currently published version lives at
 *       {@code rag:publish:{tenant}:{kbId}} as a numeric string.</li>
 * </ul>
 *
 * <p>Search uses RediSearch dialect-2 KNN syntax in pre-filter form
 * (<code>&lt;filter&gt;=&gt;[KNN $K @embedding $BLOB AS score]</code>) — the
 * spec §8.1 filters run server-side before the HNSW traversal, so EF_RUNTIME
 * directly controls recall without application-side waste.</p>
 *
 * <p>All Jedis / RediSearch failures are funnelled into
 * {@link VectorStoreUnavailableException} (spec §10 row 3).</p>
 */
public class RedisVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorStore.class);

    /** Alias name that {@link #search()} queries — decoupled from the versioned index. */
    public static final String ACTIVE_ALIAS_PREFIX = "rag:active:";

    private final RedisConnection connection;
    private final RedisIndexManager indexManager;
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker circuitBreaker; // may be null in hermetic tests

    public RedisVectorStore(RedisConnection connection, RedisIndexManager indexManager) {
        this(connection, indexManager, new SimpleMeterRegistry(), null);
    }

    public RedisVectorStore(RedisConnection connection, RedisIndexManager indexManager, MeterRegistry meterRegistry) {
        this(connection, indexManager, meterRegistry, null);
    }

    /**
     * Production constructor — wires the {@code redis} circuit breaker from
     * the auto-configured {@link CircuitBreakerRegistry}. The breaker trips
     * when consecutive Redis failures (connection refused, JedisException)
     * exceed the threshold configured in {@code application.yml} under
     * {@code resilience4j.circuitbreaker.instances.redis}. While OPEN, calls
     * short-circuit with {@link CallNotPermittedException}, mapped to
     * {@link VectorStoreUnavailableException} — the same type the inner
     * catch-all produces, so callers degrade uniformly to "no results".
     */
    public RedisVectorStore(RedisConnection connection,
                            RedisIndexManager indexManager,
                            MeterRegistry meterRegistry,
                            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.connection = connection;
        this.indexManager = indexManager;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = (circuitBreakerRegistry == null) ? null
                : circuitBreakerRegistry.circuitBreaker("redis");
    }

    // ─── upsert ────────────────────────────────────────────────────────────

    @Override
    public int upsert(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        UnifiedJedis client = connection.client();
        int written = 0;
        try {
            for (Chunk chunk : chunks) {
                long version = kbVersionOf(chunk);
                boolean staging = chunk.status() == ChunkStatus.STAGING;

                // Ensure the destination index exists before HSET — RediSearch
                // will pick up the new fields (including the binary embedding)
                // automatically because the schema has HASH PREFIX matching.
                indexManager.ensureIndex(chunk.tenantId(), version, staging);

                String hashKey = indexManager.chunkKey(chunk.tenantId(), chunk.chunkId());
                client.hset(hashKey, RedisChunkCodec.toHashFields(chunk));
                // Embedding is binary (FLOAT32 little-endian) — use the byte[] overload.
                client.hset(hashKey.getBytes(), "embedding".getBytes(),
                        RedisChunkCodec.toEmbeddingBytes(chunk.embedding()));
                written++;
            }
            log.debug("Upserted {} chunks", written);
            return written;
        } catch (Exception e) {
            throw new VectorStoreUnavailableException("upsert failed for " + chunks.size() + " chunks", e);
        }
    }

    // ─── deleteByIds ───────────────────────────────────────────────────────

    @Override
    public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        UnifiedJedis client = connection.client();
        try {
            int removed = 0;
            for (String id : chunkIds) {
                removed += (int) client.del(indexManager.chunkKey(tenantId, id));
            }
            log.debug("deleteByIds tenant={} kb={} v={} → removed {}", tenantId, kbId, kbVersion, removed);
            return removed;
        } catch (Exception e) {
            throw new VectorStoreUnavailableException(
                    "deleteByIds failed for tenant=" + tenantId + " kb=" + kbId, e);
        }
    }

    // ─── search ────────────────────────────────────────────────────────────

    @Override
    public List<Chunk> search(
            float[] queryVector,
            String tenantId,
            String kbId,
            long kbVersion,
            List<String> userPermissionTags,
            PermissionMode permissionMode,
            int topK
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (queryVector == null || queryVector.length == 0) {
                throw new IllegalArgumentException("queryVector must not be empty");
            }
            UnifiedJedis client = connection.client();
            long versionToSearch = resolveActiveVersion(client, tenantId, kbId, kbVersion);
            String indexAlias = ACTIVE_ALIAS_PREFIX + tenantId + ":" + kbId;

            long nowSec = System.currentTimeMillis() / 1000L;
            String filter = buildPreFilter(tenantId, kbId, versionToSearch,
                    userPermissionTags, permissionMode, nowSec);
            String knnQuery = "(" + filter + ")=>[KNN $K @embedding $BLOB AS score]";

            // AND mode requires a post-filter pass to drop chunks that pass the
            // server-side pre-filter but still have a permission tag the user is
            // missing. We over-fetch by a small multiplier so the post-filter
            // doesn't leave us short of `topK` results.
            int fetchLimit = (permissionMode == PermissionMode.AND && userPermissionTags != null
                    && !userPermissionTags.isEmpty())
                    ? Math.max(topK * 4, 40)
                    : topK;

            FTSearchParams params = FTSearchParams.searchParams()
                    .dialect(2)
                    .addParam("K", fetchLimit)
                    .addParam("BLOB", RedisChunkCodec.toEmbeddingBytes(queryVector))
                    .withScores()
                    .limit(0, fetchLimit);

            // Wrap ALL Redis calls (alias probe + KNN search) in a circuit breaker.
            // The breaker counts every JedisException / ConnectionException that
            // falls through to VectorStoreUnavailableException; once the trip
            // threshold is hit, subsequent calls short-circuit and we spare Redis
            // from the hammering that usually accompanies an outage.
            java.util.function.Supplier<List<Chunk>> upstream = () -> {
                // Step 1: alias probe — verify the active index exists.
                try {
                    client.ftSearch(indexAlias, "*",
                            FTSearchParams.searchParams().limit(0, 0).dialect(2));
                } catch (redis.clients.jedis.exceptions.JedisException ex) {
                    String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                    if (msg.contains("no such index") || msg.contains("no such alias")
                            || msg.contains("unknown index") || msg.contains("alias")
                            || msg.contains("does not exist")) {
                        throw new KbNotFoundException(
                                "no active index for tenant=" + tenantId + " kb=" + kbId
                                        + " v=" + versionToSearch);
                    }
                    // Real Redis error (connection / breaker / etc) — the CB
                    // records this failure because VectorStoreUnavailableException
                    // is in its recordExceptions list.
                    throw new VectorStoreUnavailableException(
                            "FT.SEARCH probe failed for alias " + indexAlias, ex);
                }
                // Step 2: KNN search
                SearchResult searchResult = client.ftSearch(indexAlias, knnQuery, params);
                List<Chunk> raw = mapResultToChunks(client, searchResult, tenantId, kbId);
                return applyAndPermissionFilter(raw, userPermissionTags, permissionMode, topK);
            };

            java.util.function.Supplier<List<Chunk>> guarded =
                    (circuitBreaker == null) ? upstream
                            : CircuitBreaker.decorateSupplier(circuitBreaker, upstream);
            try {
                return guarded.get();
            } catch (CallNotPermittedException ex) {
                // Breaker OPEN — let the typed message bubble up unchanged so
                // operators can tell "tripped" from "upstream failed" in logs.
                throw new VectorStoreUnavailableException(
                        "Redis circuit breaker OPEN — skipping search for tenant=" + tenantId
                                + " kb=" + kbId, ex);
            } catch (KbNotFoundException ex) {
                // Logical (not infrastructural) — let QAService degrade to
                // FALLBACK_RULE without tripping the breaker.
                throw ex;
            } catch (VectorStoreUnavailableException ex) {
                // Already a typed exception (e.g. from the probe above) — don't re-wrap.
                throw ex;
            } catch (Exception e) {
                throw new VectorStoreUnavailableException(
                        "search failed for tenant=" + tenantId + " kb=" + kbId + " v=" + versionToSearch, e);
            }
        } finally {
            sample.stop(Timer.builder("rag.redis.hnsw.search.ms")
                    .tag("tenant", tenantId)
                    .register(meterRegistry));
        }
    }

    private static List<Chunk> applyAndPermissionFilter(
            List<Chunk> raw,
            List<String> userTags,
            PermissionMode mode,
            int topK
    ) {
        if (mode != PermissionMode.AND || userTags == null || userTags.isEmpty() || raw.isEmpty()) {
            if (raw.size() > topK) {
                return raw.subList(0, topK);
            }
            return raw;
        }
        Set<String> userSet = Set.copyOf(userTags);
        List<Chunk> out = new ArrayList<>(Math.min(raw.size(), topK));
        for (Chunk c : raw) {
            // AND semantics (spec §8.2 default): user's tag set must be a
            // superset of chunk's tag set — every tag on the chunk must be
            // held by the user.
            if (c.permissionTags().isEmpty() || c.permissionTags().stream().allMatch(userSet::contains)) {
                out.add(c);
                if (out.size() >= topK) break;
            }
        }
        return out;
    }

    private static String buildPreFilter(
            String tenantId,
            String kbId,
            long versionCeiling,
            List<String> userTags,
            PermissionMode mode,
            long nowSec
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("@tenantId:{").append(escapeTag(tenantId)).append("}");
        sb.append(" @kbId:{").append(escapeTag(kbId)).append("}");
        sb.append(" @documentVersion:[-inf ").append(versionCeiling).append("]");
        sb.append(" @status:{ACTIVE}");
        sb.append(" @publishedAt:[-inf ").append(nowSec).append("]");
        if (userTags == null || userTags.isEmpty()) {
            // No authority ⇒ no chunks should match. Use a guaranteed-impossible tag.
            sb.append(" @permissionTags:{__no_such_tag_4242__}");
            return sb.toString();
        }
        if (mode == PermissionMode.OR) {
            String joined = userTags.stream()
                    .map(RedisVectorStore::escapeTag)
                    .collect(Collectors.joining("|"));
            sb.append(" @permissionTags:{").append(joined).append("}");
        } else {
            // AND mode: RediSearch cannot express "user ⊇ chunk" natively, so
            // we use the same server-side INTERSECTION pre-filter as OR — that
            // is, the chunk must contain AT LEAST ONE of the user's tags. We
            // then enforce the strict superset check client-side in
            // applyAndPermissionFilter().
            String joined = userTags.stream()
                    .map(RedisVectorStore::escapeTag)
                    .collect(Collectors.joining("|"));
            sb.append(" @permissionTags:{").append(joined).append("}");
        }
        return sb.toString();
    }

    private long resolveActiveVersion(UnifiedJedis client, String tenantId, String kbId, long requested) {
        if (requested > 0) {
            return requested;
        }
        if (kbId == null || kbId.isBlank()) {
            // No kbId supplied — caller never told us which KB to look at.
            // Treat as "no data" rather than "infrastructure failure".
            throw new KbNotFoundException("no kbId supplied");
        }
        try {
            String v = client.get(indexManager.publishPointerKey(tenantId, kbId));
            if (v == null) {
                // KB was never published (or fully cleaned up) — not a redis outage.
                throw new KbNotFoundException(
                        "no published version for tenant=" + tenantId + " kb=" + kbId);
            }
            long published = Long.parseLong(v);
            if (published <= 0) {
                // Publish pointer is corrupt — this IS a data integrity problem
                // (corrupt storage), so it stays a 503 VectorStoreUnavailable.
                throw new VectorStoreUnavailableException(
                        "publish pointer corrupt for tenant=" + tenantId + " kb=" + kbId + " (v=" + v + ")");
            }
            return published;
        } catch (NumberFormatException nfe) {
            throw new VectorStoreUnavailableException(
                    "publish pointer corrupt for tenant=" + tenantId + " kb=" + kbId, nfe);
        }
    }

    private List<Chunk> mapResultToChunks(UnifiedJedis client, SearchResult result, String tenantId, String kbId) {
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return List.of();
        }
        List<Chunk> out = new ArrayList<>(result.getDocuments().size());
        for (Document doc : result.getDocuments()) {
            String hashKey = doc.getId();
            Map<String, String> fields = client.hgetAll(hashKey);
            if (fields == null || fields.isEmpty()) {
                log.warn("Search hit {} vanished from HASH before rehydrate — skipping", hashKey);
                continue;
            }
            byte[] embedding = client.hget(hashKey.getBytes(), "embedding".getBytes());
            String chunkId = fields.getOrDefault(
                    "chunkId", hashKey.substring(hashKey.lastIndexOf(':') + 1));
            Chunk c = RedisChunkCodec.fromHashFields(chunkId, fields, embedding);
            // Defense in depth — server-side filter is the source of truth, but
            // we never hand back a chunk from a different tenant or KB even if
            // a misconfiguration leaks through.
            if (!tenantId.equals(c.tenantId()) || !kbId.equals(c.kbId())) {
                log.error("Filter bypass! tenant mismatch on chunk {} — discarding", chunkId);
                continue;
            }
            out.add(c);
        }
        return out;
    }

    // ─── publish ───────────────────────────────────────────────────────────

    @Override
    public void publish(String tenantId, String kbId, long kbVersion) {
        UnifiedJedis client = connection.client();
        String stagingIndex = indexManager.stagingIndexName(tenantId, kbVersion);
        String activeIndex = indexManager.indexName(tenantId, kbVersion);
        String alias = ACTIVE_ALIAS_PREFIX + tenantId + ":" + kbId;

        try {
            // 1) Ensure the post-publish index exists with the same schema
            //    (vector dim + HNSW params must match the staging index).
            indexManager.ensureIndex(tenantId, kbVersion, /* staging = */ false);

            // 2) Promote every STAGING chunk in the staging index to ACTIVE.
            //    IMPORTANT: do NOT re-HSET the entire hash — that wipes the
            //    binary embedding field for a moment and triggers a
            //    `hash_indexing_failures` event in the active index. We only
            //    need to flip the status field; RediSearch picks up the
            //    TAG-field change and reindexes the hash in both the staging
            //    and the active index (because both share the same PREFIX).
            //
            //    NB on RediSearch semantics: a hash is indexed under an index
            //    if at the moment of HSET (or hash creation) the hash matched
            //    the index's PREFIX. Since `indexManager.ensureIndex(active)`
            //    runs BEFORE this loop and the index creation is what binds
            //    existing hashes, the chunks from the staging-side HSET in
            //    `upsert()` get picked up by the new active index the moment
            //    it scans. Then the status flip below reindexes them with
            //    the right visibility value.
            final int pageSize = 10_000;
            long offset = 0;
            int promoted = 0;
            while (true) {
                FTSearchParams pageParams = FTSearchParams.searchParams()
                        .dialect(2)
                        .limit((int) offset, pageSize);
                SearchResult page = client.ftSearch(stagingIndex, "*", pageParams);
                if (page == null || page.getDocuments() == null || page.getDocuments().isEmpty()) {
                    break;
                }
                for (Document doc : page.getDocuments()) {
                    client.hset(doc.getId(), "status", ChunkStatus.ACTIVE.name());
                    promoted++;
                }
                if (page.getDocuments().size() < pageSize) {
                    break;
                }
                offset += pageSize;
            }
            log.info("publish: promoted {} chunks from staging {} to active {}",
                    promoted, stagingIndex, activeIndex);

            // 3) Atomically swap the alias.
            //    ftAliasUpdate is one server-side op; if no prior alias exists
            //    it throws — fall through to ftAliasAdd.
            try {
                client.ftAliasUpdate(alias, activeIndex);
                log.info("publish: alias {} → {} (updated from previous)", alias, activeIndex);
            } catch (Exception missingOrNew) {
                client.ftAliasAdd(alias, activeIndex);
                log.info("publish: alias {} → {} (newly added)", alias, activeIndex);
            }

            // 4) Read old publish pointer for deprecation (before overwriting).
            String oldVersionStr = client.get(indexManager.publishPointerKey(tenantId, kbId));
            long oldKbVersion = oldVersionStr == null ? -1L : Long.parseLong(oldVersionStr);

            // 5) Persist the publish pointer for resolveActiveVersion() callers.
            indexManager.setPublishPointer(tenantId, kbId, kbVersion);

            // 6) Deprecate old version chunks (spec §6.4).
            //    Old chunks get status=DEPRECATED so the @status:{ACTIVE} pre-filter
            //    no longer returns them on the alias. Cleanup (async, 7-day TTL) is
            //    the deployment / ops concern — see spec §6.4.
            if (oldKbVersion > 0 && oldKbVersion != kbVersion) {
                int deprecated = deprecate(tenantId, kbId, oldKbVersion);
                log.info("publish: deprecated {} old-V{} chunks for {}/{}",
                        deprecated, oldKbVersion, tenantId, kbId);
            }
        } catch (Exception e) {
            throw new VectorStoreUnavailableException(
                    "publish failed for tenant=" + tenantId + " kb=" + kbId + " v=" + kbVersion, e);
        }
    }

    // ─── deprecate ─────────────────────────────────────────────────────────

    @Override
    public int deprecate(String tenantId, String kbId, long oldKbVersion) {
        UnifiedJedis client = connection.client();
        String alias = ACTIVE_ALIAS_PREFIX + tenantId + ":" + kbId;
        try {
            // Find all ACTIVE docs at this version, then flip their status to
            // DEPRECATED in the HASH — RediSearch picks up the change because
            // the schema declares status as a TAG field.
            String query = "@tenantId:{" + escapeTag(tenantId) + "}"
                    + " @kbId:{" + escapeTag(kbId) + "}"
                    + " @documentVersion:[" + oldKbVersion + " " + oldKbVersion + "]"
                    + " @status:{ACTIVE}";
            SearchResult res = client.ftSearch(alias, query,
                    FTSearchParams.searchParams().dialect(2).limit(0, 10_000));
            if (res == null || res.getDocuments() == null || res.getDocuments().isEmpty()) {
                return 0;
            }
            int updated = 0;
            for (Document doc : res.getDocuments()) {
                client.hset(doc.getId(), "status", ChunkStatus.DEPRECATED.name());
                updated++;
            }
            log.info("deprecate: tenant={} kb={} oldV={} → {} chunks marked DEPRECATED",
                    tenantId, kbId, oldKbVersion, updated);
            return updated;
        } catch (Exception e) {
            throw new VectorStoreUnavailableException(
                    "deprecate failed for tenant=" + tenantId + " kb=" + kbId + " v=" + oldKbVersion, e);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /** Escape RediSearch TAG field literal metacharacters.
     *  Per RediSearch docs: a backslash escapes any character that is not
     *  [a-zA-Z0-9_\-]. We pre-emptively escape the high-risk ones plus '-' and '/'
     *  because they're commonly found in kbIds / tenantIds and otherwise break
     *  the query parser ("Syntax error at offset N near kb"). */
    static String escapeTag(String s) {
        return s.replace("\\", "\\\\")
                .replace("-", "\\-")
                .replace("/", "\\/")
                .replace(".", "\\.")
                .replace(",", "\\,")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("|", "\\|")
                .replace(":", "\\:")
                .replace(" ", "\\ ");
    }

    private static long kbVersionOf(Chunk c) {
        try {
            return Long.parseLong(c.documentVersion());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Chunk.documentVersion must be numeric (got '" + c.documentVersion() + "')", ex);
        }
    }

    /** Ensure both the active and staging indexes exist for a (tenant, kbVersion) pair. */
    public void ensureIndexes(String tenantId, long kbVersion) {
        indexManager.ensureIndex(tenantId, kbVersion, false);
        indexManager.ensureIndex(tenantId, kbVersion, true);
    }

    /** Used by tests — exposes the alias naming convention. */
    public static String aliasName(String tenantId, String kbId) {
        return ACTIVE_ALIAS_PREFIX + tenantId + ":" + kbId;
    }
}
