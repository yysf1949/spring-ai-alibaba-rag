/**
 * rag-app — Spring Boot 3.3 application entry point.
 *
 * <p>Wires together rag-core + rag-embedding + rag-redis + rag-pipeline and exposes
 * REST endpoints (POST /ingest, POST /qa, GET /ingest/{jobId}).</p>
 *
 * <p>Design spec §13.12.</p>
 */
package io.github.yysf1949.rag.app;