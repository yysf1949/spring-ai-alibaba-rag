/**
 * rag-core — pure domain layer.
 *
 * <p>Contains only records (Chunk, Query, Answer, Tenant, KnowledgeBase),
 * port interfaces (VectorStore, EmbeddingGateway, RerankService, RewriteService),
 * and shared exceptions. <strong>No Spring, no Redis, no LLM client.</strong></p>
 *
 * <p>Implements design spec §4 (core data model) and §13.4 (Java records).</p>
 */
package io.github.yysf1949.rag.core;