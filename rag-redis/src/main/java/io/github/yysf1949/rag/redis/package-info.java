/**
 * rag-redis — Redis adapters for rag-core ports.
 *
 * <ul>
 *   <li>vector — RedisVectorStore, RedisIndexManager, VectorRepository
 *       (HNSW via RediSearch, design spec §12)</li>
 *   <li>cache — AnswerCache, EmbeddingCache, RewriteCache (TTL + LRU via Redis)</li>
 *   <li>session — SessionStore</li>
 * </ul>
 */
package io.github.yysf1949.rag.redis;