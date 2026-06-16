/**
 * rag-pipeline — orchestration of ingest + online QA chains.
 *
 * <ul>
 *   <li>ingest  — IngestService, ChunkSplitter, StagingIndexManager (spec §10, §6)</li>
 *   <li>rewrite — RuleBasedQueryRewriter + LLM fallback (§11.2)</li>
 *   <li>rerank  — RerankService + DashScope gte-rerank impl (§11.3)</li>
 *   <li>context — ContextAssembler — token budget control (§13.10)</li>
 *   <li>qa      — QAService — cache → rewrite → retrieve → rerank → generate → degrade (§11.1, §13.11)</li>
 * </ul>
 */
package io.github.yysf1949.rag.pipeline;