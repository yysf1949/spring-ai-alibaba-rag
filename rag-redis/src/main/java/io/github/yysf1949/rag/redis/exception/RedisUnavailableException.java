package io.github.yysf1949.rag.redis.exception;

import io.github.yysf1949.rag.redis.exception.RedisUnavailableException;

/**
 * Runtime exception for any Redis-side failure — wraps Jedis / pool / IO errors
 * so callers (VectorStore / caches) can degrade per design spec §10 row 3.
 *
 * <p>Maps to HTTP 503 + {@code Retry-After} at the controller layer.</p>
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message) {
        super(message);
    }

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}