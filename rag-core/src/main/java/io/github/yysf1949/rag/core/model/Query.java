package io.github.yysf1949.rag.core.model;

import java.util.Set;

/**
 * User-supplied retrieval request — everything the QA pipeline needs to
 * produce an answer.
 *
 * <p>Design spec §4, §8.1 — the {@code permissionTags} field is the user's
 * authority; chunks whose {@code permissionTags} do not intersect this set
 * (under the configured mode) are filtered out at retrieval time.</p>
 *
 * @param tenantId         hard wall (spec §8.1)
 * @param userId           for logging + metrics
 * @param sessionId        multi-turn context handle
 * @param rawText          unprocessed user question
 * @param permissionTags   user's authority tags
 * @param topK             retrieval pool size (default 20, spec §8.1)
 * @param kbVersion        pinned KB version — null ⇒ use currently published
 */
public record Query(
        String tenantId,
        String userId,
        String sessionId,
        String rawText,
        Set<String> permissionTags,
        int topK,
        KbVersion kbVersion
) {

    public Query {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText must not be blank");
        }
        permissionTags = permissionTags == null ? Set.of() : Set.copyOf(permissionTags);
        if (topK <= 0) {
            topK = 20;
        }
    }

    /**
     * Convenience constructor — uses currently-published version of {@code kbId}.
     * Provided as a static factory because the most common call site does
     * not want to specify a version.
     */
    public static Query of(
            String tenantId,
            String userId,
            String sessionId,
            String rawText,
            Set<String> permissionTags
    ) {
        return new Query(tenantId, userId, sessionId, rawText, permissionTags, 20, null);
    }
}