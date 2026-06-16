package io.github.yysf1949.rag.redis.vector;

import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.exception.RedisUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;
import redis.clients.jedis.search.SearchProtocol.SearchCommand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RediSearch index lifecycle — creates the per-(tenant, kbVersion) HNSW index
 * with the schema mandated by design spec §5.2 / §12.2 and exposes
 * publish/deprecate helpers.
 *
 * <p>Schema (spec §5.2):
 * <ul>
 *   <li>{@code chunkId}        TAG     (primary key)</li>
 *   <li>{@code tenantId}       TAG     (multi-tenant hard wall)</li>
 *   <li>{@code kbId}           TAG</li>
 *   <li>{@code documentId}     TAG</li>
 *   <li>{@code documentVersion} NUMERIC</li>
 *   <li>{@code status}         TAG     (STAGING / ACTIVE / DEPRECATED)</li>
 *   <li>{@code publishedAt}    NUMERIC (epoch seconds — used for sort + cutoff)</li>
 *   <li>{@code permissionTags} TAG[]   (multi-value, separator '|')</li>
 *   <li>{@code title}          TEXT</li>
 *   <li>{@code content}        TEXT</li>
 *   <li>{@code sourceUri}      TEXT</li>
 *   <li>{@code sectionPath}    TEXT</li>
 *   <li>{@code embedding}      VECTOR HNSW 1536 (FLOAT32, COSINE)</li>
 * </ul>
 *
 * <p>Built on Jedis 5.2's official RediSearch DSL
 * ({@link Schema}, {@link IndexOptions}, {@link IndexDefinition}) plus a raw
 * {@code FT._LIST} call via {@code sendCommand} for the (undocumented) listing.</p>
 */
public class RedisIndexManager {

    private static final Logger log = LoggerFactory.getLogger(RedisIndexManager.class);

    /** Default embedding dimension — DashScope text-embedding-v3. */
    public static final int DEFAULT_DIM = 1536;

    /** Default HNSW parameters (spec §12.3). */
    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_EF_CONSTRUCTION = 200;
    public static final int DEFAULT_EF_RUNTIME = 10;

    private final RedisConnection connection;
    private final int dimension;
    private final int m;
    private final int efConstruction;
    private final int efRuntime;

    public RedisIndexManager(RedisConnection connection) {
        this(connection, DEFAULT_DIM, DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_RUNTIME);
    }

    public RedisIndexManager(
            RedisConnection connection,
            int dimension,
            int m,
            int efConstruction,
            int efRuntime
    ) {
        this.connection = connection;
        this.dimension = dimension;
        this.m = m;
        this.efConstruction = efConstruction;
        this.efRuntime = efRuntime;
    }

    // ─── Index naming (spec §5.1) ────────────────────────────────────────────

    public String indexName(String tenantId, long kbVersion) {
        return "rag:index:" + tenantId + ":" + kbVersion;
    }

    public String stagingIndexName(String tenantId, long kbVersion) {
        return "rag:index:" + tenantId + ":" + kbVersion + "-staging";
    }

    public String chunkKey(String tenantId, String chunkId) {
        return "rag:chunk:" + tenantId + ":" + chunkId;
    }

    public String chunkKeyPrefix(String tenantId) {
        return "rag:chunk:" + tenantId + ":";
    }

    public String publishPointerKey(String tenantId, String kbId) {
        return "rag:publish:" + tenantId + ":" + kbId;
    }

    // ─── Schema builder ────────────────────────────────────────────────────

    /**
     * Build the RediSearch schema with HNSW vector parameters (spec §5.2).
     */
    public Schema buildSchema() {
        Schema schema = new Schema();
        schema.addTagField("chunkId");
        schema.addTagField("tenantId");
        schema.addTagField("kbId");
        schema.addTagField("documentId");
        schema.addSortableNumericField("documentVersion");
        schema.addTagField("status");
        schema.addSortableNumericField("publishedAt");
        // permissionTags is multi-value, separator '|'
        schema.addTagField("permissionTags", "|");
        schema.addTextField("title", 1.0);
        schema.addTextField("sectionPath", 0.5);
        schema.addTextField("sourceUri", 0.5);
        schema.addTextField("content", 1.0);
        // HNSW vector field — attributes supplied as a Map
        Map<String, Object> vectorAttrs = new HashMap<>();
        vectorAttrs.put("TYPE", "FLOAT32");
        vectorAttrs.put("DIM", dimension);
        vectorAttrs.put("DISTANCE_METRIC", "COSINE");
        vectorAttrs.put("M", m);
        vectorAttrs.put("EF_CONSTRUCTION", efConstruction);
        vectorAttrs.put("EF_RUNTIME", efRuntime);
        schema.addHNSWVectorField("embedding", vectorAttrs);
        return schema;
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Create the active (or staging) index for a kbVersion if it does not exist.
     */
    public void ensureIndex(String tenantId, long kbVersion, boolean staging) {
        String name = staging ? stagingIndexName(tenantId, kbVersion) : indexName(tenantId, kbVersion);
        IndexDefinition definition = new IndexDefinition(IndexDefinition.Type.HASH)
                .setPrefixes(chunkKeyPrefix(tenantId));
        // Note: RediSearch 2.4+ removed FILTER from FT.CREATE — tenant isolation
        // is enforced via the PREFIX (chunks live under rag:chunk:{tenantId}:*)
        // AND a server-side @tenantId={...} clause in every search query.
        IndexOptions options = IndexOptions.defaultOptions().setDefinition(definition);

        UnifiedJedis client = connection.client();
        try {
            try {
                client.ftInfo(name);
                log.debug("Index already exists: {}", name);
                return;
            } catch (JedisDataException notFound) {
                // expected — index doesn't exist, create it
            }

            String resp = client.ftCreate(name, options, buildSchema());
            log.info("Created index: {} (resp={})", name, resp);
        } catch (Exception e) {
            throw new RedisUnavailableException("Failed to create index " + name, e);
        }
    }

    /**
     * Drop an index and its hash data. Idempotent.
     */
    public void dropIndex(String tenantId, long kbVersion, boolean staging, boolean dropData) {
        String name = staging ? stagingIndexName(tenantId, kbVersion) : indexName(tenantId, kbVersion);
        UnifiedJedis client = connection.client();
        try {
            try {
                String resp = dropData
                        ? client.ftDropIndexDD(name)
                        : client.ftDropIndex(name);
                log.info("Dropped index: {} (data={}, resp={})", name, dropData, resp);
            } catch (JedisDataException notFound) {
                log.debug("Index to drop did not exist: {}", name);
            }
        } catch (Exception e) {
            throw new RedisUnavailableException("Failed to drop index " + name, e);
        }
    }

    /**
     * List all index names matching {@code rag:index:{tenant}:*}.
     */
    public List<String> listIndexes(String tenantId) {
        try {
            // FT._LIST returns bulk-string reply which Jedis 5 surfaces as List<byte[]>.
            // Convert each entry to UTF-8 String before filtering.
            @SuppressWarnings("unchecked")
            List<byte[]> raw = (List<byte[]>) connection.client().sendCommand(SearchCommand._LIST);
            String prefix = "rag:index:" + tenantId + ":";
            return raw.stream()
                    .map(b -> b == null ? "" : new String(b, java.nio.charset.StandardCharsets.UTF_8))
                    .filter(n -> n.startsWith(prefix))
                    .toList();
        } catch (Exception e) {
            throw new RedisUnavailableException("Failed to list indexes for " + tenantId, e);
        }
    }

    // ─── Publish pointer (spec §5.1) ────────────────────────────────────────

    public void setPublishPointer(String tenantId, String kbId, long kbVersion) {
        try {
            connection.client().set(publishPointerKey(tenantId, kbId), String.valueOf(kbVersion));
        } catch (Exception e) {
            throw new RedisUnavailableException("Failed to set publish pointer", e);
        }
    }

    public long getPublishPointer(String tenantId, String kbId) {
        try {
            String v = connection.client().get(publishPointerKey(tenantId, kbId));
            return v == null ? -1L : Long.parseLong(v);
        } catch (Exception e) {
            throw new RedisUnavailableException("Failed to read publish pointer", e);
        }
    }

    public int dimension() {
        return dimension;
    }
}