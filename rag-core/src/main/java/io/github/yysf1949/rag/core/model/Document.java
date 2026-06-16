package io.github.yysf1949.rag.core.model;

import java.util.List;
import java.util.Set;

/**
 * Raw document awaiting ingestion — design spec §6.1 step "解析 (PDF/Word/MD)".
 *
 * <p>This is the input shape for {@code rag-pipeline}'s splitter. Fields
 * here are <em>inherited</em> by every emitted {@link Chunk} so the
 * splitter never has to re-look them up. The {@code sections} list is the
 * parsed tree (heading path + body), produced by the upstream parser
 * (PDF/Word/Markdown). When a caller doesn't have a real parser, they can
 * pass a single section with {@code heading=null} and {@code body=fullText}.</p>
 *
 * <p>Design note: we keep {@link Document} as a record (immutable, easy to
 * transport across modules) and let the splitter produce a list of
 * {@link Chunk}s with the same {@code tenantId/kbId/documentId/version/title}
 * but distinct {@code chunkId}, {@code content}, and {@code sectionPath}.</p>
 *
 * @param tenantId         owning tenant (hard wall, propagated to every chunk)
 * @param kbId             knowledge base id
 * @param documentId       document id, unique within the KB
 * @param documentVersion  semantic version string (e.g. {@code "1"} or
 *                         {@code "2026-06-16.1"}); embedded as a string
 *                         so future schema evolution doesn't have to migrate longs
 * @param title            document title (e.g. filename or h1)
 * @param sourceUri        canonical URL or storage path
 * @param permissionTags   tags inherited by every emitted chunk
 * @param sections         parsed section tree
 */
public record Document(
        String tenantId,
        String kbId,
        String documentId,
        String documentVersion,
        String title,
        String sourceUri,
        Set<String> permissionTags,
        List<Section> sections
) {

    public Document {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (documentVersion == null || documentVersion.isBlank()) {
            throw new IllegalArgumentException("documentVersion must not be blank");
        }
        if (title == null) title = "";
        if (sourceUri == null) sourceUri = "";
        permissionTags = permissionTags == null ? Set.of() : Set.copyOf(permissionTags);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * A single section inside a {@link Document}. Sections form a flat list —
     * the heading is a {@code /}-delimited path (e.g. {@code "退款规则/运费条款"}).
     * The splitter joins path parts and embeds the joined string in each
     * emitted chunk's {@code sectionPath} field.
     *
     * @param heading   breadcrumb path, may be empty for top-level body
     * @param body      raw text
     */
    public record Section(String heading, String body) {
        public Section {
            if (body == null) {
                throw new IllegalArgumentException("Section.body must not be null");
            }
            if (heading == null) heading = "";
        }

        /** Convenience for the common case of a single body-only document. */
        public static Section bodyOnly(String body) {
            return new Section("", body);
        }
    }
}
