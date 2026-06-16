package io.github.yysf1949.rag.redis.config;

/**
 * Strongly-typed configuration for the Redis pool.
 *
 * <p>Bound from {@code spring.rag.redis.*} — see
 * {@code rag-app/src/main/resources/application.yml} (Phase 6).</p>
 *
 * @param host             Redis host (default localhost)
 * @param port             Redis port (default 6379)
 * @param password         optional AUTH password (nullable)
 * @param database         logical DB number (default 0)
 * @param maxTotal         pool max size
 * @param maxIdle          pool max idle
 * @param minIdle          pool min idle
 * @param maxWaitMs        max block time waiting for a free connection
 * @param commandTimeoutMs per-command timeout
 */
public record RedisProperties(
        String host,
        int port,
        String password,
        int database,
        int maxTotal,
        int maxIdle,
        int minIdle,
        long maxWaitMs,
        long commandTimeoutMs
) {

    public RedisProperties {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = 6379;
        }
        if (database < 0) {
            database = 0;
        }
        if (maxTotal <= 0) {
            maxTotal = 32;
        }
        if (maxIdle <= 0) {
            maxIdle = 16;
        }
        if (minIdle < 0) {
            minIdle = 2;
        }
        if (maxWaitMs <= 0) {
            maxWaitMs = 2_000;
        }
        if (commandTimeoutMs <= 0) {
            commandTimeoutMs = 5_000;
        }
    }
}