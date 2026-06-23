package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.DocumentVersionMeta;

import java.util.List;

/**
 * Phase 19 — response payload for {@link DocumentVersionTool}.
 *
 * @param action        the action that produced this response (echoes request)
 * @param versions      list of {@link DocumentVersionMeta} for LIST; empty otherwise
 * @param message       human-readable status message ("OK", "switched to v3", etc.)
 * @param activeVersion currently-active version id, or {@code null} if none
 */
public record DocumentVersionResponse(
        DocumentVersionAction action,
        List<DocumentVersionMeta> versions,
        String message,
        Long activeVersion) {

    public DocumentVersionResponse {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (message == null) message = "OK";
    }
}
