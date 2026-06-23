package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.KbVersionMeta;

import java.util.List;

/**
 * Response shape for {@link KbVersionTool#manage}.
 *
 * @param action     echoes the action that produced this response
 * @param versions   full list of versions (populated for {@code LIST}; empty otherwise)
 * @param message    human-readable status (e.g. "switched to version 3", "no active version")
 * @param activeVersionId convenience: the new active version id (for {@code SWITCH} /
 *                   {@code ROLLBACK} / {@code GET_ACTIVE}); null otherwise
 */
public record KbVersionResponse(
        KbVersionAction action,
        List<KbVersionMeta> versions,
        String message,
        Long activeVersionId) {

    public KbVersionResponse {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (versions == null) {
            versions = List.of();
        }
        if (message == null) {
            message = "OK";
        }
    }
}