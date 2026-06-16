package io.github.yysf1949.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

/**
 * Structured eval fixture loaded from {@code src/test/resources/eval/*.json}.
 *
 * <p>Mirrors the schema defined in the spec's §18 fixture artifact.
 * All fields are populated via Jackson deserialization.</p>
 *
 * @param _comment        human-readable description (ignored at runtime)
 * @param kbId            knowledge base id
 * @param version         kb version number
 * @param sourceUri       canonical document URL
 * @param permissionTags  tags applied to the document
 * @param document        the document to ingest
 * @param query           the user query to evaluate
 * @param expected        what we expect the system to produce
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalFixture(
        @JsonProperty("_comment") String _comment,
        String kbId,
        long version,
        String sourceUri,
        Set<String> permissionTags,
        DocumentFixture document,
        QueryFixture query,
        ExpectedFixture expected
) {

    public record DocumentFixture(
            String tenantId,
            String kbId,
            String documentId,
            String title,
            List<SectionFixture> sections
    ) {}

    public record SectionFixture(
            String heading,
            String body
    ) {}

    public record QueryFixture(
            String userId,
            String rawText,
            Set<String> permissionTags,
            int topK
    ) {}

    public record ExpectedFixture(
            String mustContainSubstring,
            String mustContainSourceUri,
            List<String> expectedChunkIds
    ) {}
}