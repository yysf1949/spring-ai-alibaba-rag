package io.github.yysf1949.rag.redis.cache;

import io.github.yysf1949.rag.core.exception.CacheUnavailableException;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis-backed {@link EmbeddingCache} — design spec §13.5 + §13.7.
 *
 * <p>Each vector is stored as raw little-endian FLOAT32 bytes (same wire
 * format used by {@code RedisChunkCodec.toEmbeddingBytes}). The cache key
 * is {@code sha256(text)} — embedding hashes survive across model upgrades
 * because the input text is the same; the {@code EmbeddingGateway}
 * implementation is responsible for dimension-mismatch detection.</p>
 *
 * <p>Bulk reads ({@link #getMany(List)}) collapse N round-trips into one
 * pipeline; bulk writes ({@link #putMany(Map)}) likewise.</p>
 */
public class RedisEmbeddingCache implements EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(RedisEmbeddingCache.class);

    /** Default TTL — spec §13.7. Embeddings are model-dependent but rarely
     *  change; 7 days is generous. Override per-deployment if your model
     *  rotates faster. */
    public static final long DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60L;

    private final RedisConnection connection;
    private final long ttlSeconds;
    private final int dimension;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public RedisEmbeddingCache(RedisConnection connection, int dimension) {
        this(connection, dimension, DEFAULT_TTL_SECONDS);
    }

    public RedisEmbeddingCache(RedisConnection connection, int dimension, long ttlSeconds) {
        this.connection = connection;
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be > 0, got " + dimension);
        }
        this.dimension = dimension;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0, got " + ttlSeconds);
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public float[] get(String textHash) {
        String key = RedisCacheKeys.embeddingKey(textHash);
        try {
            byte[] blob = connection.client().get(key.getBytes(StandardCharsets.UTF_8));
            if (blob == null || blob.length == 0) {
                misses.increment();
                return null;
            }
            float[] v = decode(blob);
            if (v.length != dimension) {
                log.warn("EmbeddingCache dimension-mismatch hash={} cached={} expected={}",
                        textHash, v.length, dimension);
                connection.client().del(key);
                misses.increment();
                return null;
            }
            hits.increment();
            return v;
        } catch (Exception e) {
            misses.increment();
            log.warn("EmbeddingCache.get miss-as-failure hash={} err={}", textHash, e.getMessage());
            return null;
        }
    }

    @Override
    public List<float[]> getMany(List<String> textHashes) {
        if (textHashes == null || textHashes.isEmpty()) return List.of();
        UnifiedJedis client = connection.client();
        try {
            // Single MGET round-trip with byte[] keys.
            byte[][] keys = new byte[textHashes.size()][];
            for (int i = 0; i < textHashes.size(); i++) {
                keys[i] = RedisCacheKeys.embeddingKey(textHashes.get(i))
                        .getBytes(StandardCharsets.UTF_8);
            }
            List<byte[]> blobs = client.mget(keys);
            List<float[]> out = new ArrayList<>(blobs.size());
            for (int i = 0; i < blobs.size(); i++) {
                byte[] blob = blobs.get(i);
                if (blob == null || blob.length == 0) {
                    misses.increment();
                    out.add(null);
                    continue;
                }
                float[] v = decode(blob);
                if (v.length != dimension) {
                    log.warn("EmbeddingCache.dimension-mismatch hash={} cached={} expected={}",
                            textHashes.get(i), v.length, dimension);
                    client.del(RedisCacheKeys.embeddingKey(textHashes.get(i)));
                    misses.increment();
                    out.add(null);
                    continue;
                }
                hits.increment();
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("EmbeddingCache.getMany batch-failure n={} err={}", textHashes.size(), e.getMessage());
            misses.add(textHashes.size());
            return textHashes.stream().map(h -> (float[]) null).toList();
        }
    }

    @Override
    public void put(String textHash, float[] vector) {
        if (textHash == null || vector == null) return;
        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "vector length " + vector.length + " != configured dim " + dimension);
        }
        String key = RedisCacheKeys.embeddingKey(textHash);
        try {
            connection.client().set(
                    key.getBytes(StandardCharsets.UTF_8),
                    encode(vector),
                    SetParams.setParams().ex(ttlSeconds));
        } catch (Exception e) {
            log.warn("EmbeddingCache.put failure hash={} err={}", textHash, e.getMessage());
        }
    }

    @Override
    public void putMany(Map<String, float[]> entries) {
        if (entries == null || entries.isEmpty()) return;
        UnifiedJedis client = connection.client();
        try (var pipe = client.pipelined()) {
            for (Map.Entry<String, float[]> e : entries.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getValue().length != dimension) {
                    log.warn("EmbeddingCache.putMany skipping dim-mismatch hash={}", e.getKey());
                    continue;
                }
                byte[] keyBytes = RedisCacheKeys.embeddingKey(e.getKey())
                        .getBytes(StandardCharsets.UTF_8);
                pipe.set(keyBytes, encode(e.getValue()),
                        SetParams.setParams().ex(ttlSeconds));
            }
            pipe.sync();
        } catch (Exception ex) {
            log.warn("EmbeddingCache.putMany batch-failure n={} err={}", entries.size(), ex.getMessage());
            throw new CacheUnavailableException("putMany failed for " + entries.size() + " entries", ex);
        }
    }

    @Override
    public double hitRatio() {
        long h = hits.sum();
        long m = misses.sum();
        long t = h + m;
        return t == 0 ? 0.0 : (double) h / t;
    }

    // ─── byte codec ────────────────────────────────────────────────────────

    private static byte[] encode(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * Float.BYTES);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(v);
        return bb.array();
    }

    private static float[] decode(byte[] blob) {
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public int dimension() {
        return dimension;
    }
}
