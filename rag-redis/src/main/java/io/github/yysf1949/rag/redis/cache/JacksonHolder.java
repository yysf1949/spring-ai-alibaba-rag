package io.github.yysf1949.rag.redis.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared, lazily-built {@link ObjectMapper} tuned for caching RAG records.
 *
 * <p>We intentionally do NOT depend on Spring's auto-configured ObjectMapper
 * (which would couple this module to a running {@code SpringApplication}),
 * so this builds its own instance with the bare minimum of feature toggles
 * to round-trip {@link io.github.yysf1949.rag.core.model.Answer} and
 * {@link io.github.yysf1949.rag.core.port.RewriteService.RewriteResult}
 * without surprises.</p>
 *
 * <ul>
 *   <li>{@code JavaTimeModule} — so {@link java.time.Instant} serialises as
 *       epoch seconds (numeric), not an array.</li>
 *   <li>ISO-8601 timestamps on write; lenient on read.</li>
 *   <li>Allow field visibility for records (records have private final
 *       fields; default Jackson visibility handles this).</li>
 * </ul>
 */
final class JacksonHolder {

    private static final ObjectMapper MAPPER = build();

    private JacksonHolder() {
    }

    static ObjectMapper get() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return m;
    }
}
