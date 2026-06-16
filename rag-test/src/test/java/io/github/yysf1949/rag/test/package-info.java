/**
 * rag-test — integration tests + §18 refund-rule end-to-end demo.
 *
 * <p>Testcontainers Redis Stack + Mock DashScope by default; real DashScope
 * via {@code @EnabledIfEnvironmentVariable("DASHSCOPE_API_KEY")}.
 * Design spec §11, §18.</p>
 */
package io.github.yysf1949.rag.test;