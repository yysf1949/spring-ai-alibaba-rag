/**
 * rag-embedding — DashScope adapters for EmbeddingGateway, RerankService, LlmService.
 *
 * <p>This package ships the {@code Stub} implementations that {@link io.github.yysf1949.rag.app.config.BeansConfig}
 * wires when no real DashScope bean is on the classpath. Production wiring (Phase
 * 5-P4) replaces each stub with a DashScope-backed implementation in the
 * {@code io.github.yysf1949.rag.embedding.dashscope} sub-package; the
 * stubs stay in place for dev / smoke / unit tests.</p>
 */
package io.github.yysf1949.rag.embedding.stub;