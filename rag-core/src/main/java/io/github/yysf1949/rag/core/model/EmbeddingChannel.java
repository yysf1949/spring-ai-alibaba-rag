package io.github.yysf1949.rag.core.model;

/**
 * Which embedding provider produced this chunk's vector.
 *
 * <p>Written at ingest time and stored in the chunk hash (spec §6.5).
 * Default is {@link #STUB_HASH} when no SiliconFlow key is configured.
 *
 * <p>When SiliconFlow is active (class-level conditional on
 * {@code rag.siliconflow.enabled=true + api-key non-blank}), chunks
 * carry {@link #SILICONFLOW_BGE_M3} so downstream monitoring and
 * A/B evaluation can distinguish embedding quality by provider.
 */
public enum EmbeddingChannel {
    STUB_HASH,
    SILICONFLOW_BGE_M3
}
