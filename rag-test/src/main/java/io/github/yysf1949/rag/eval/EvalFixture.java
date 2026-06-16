package io.github.yysf1949.rag.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * JSON-deserializable eval fixture — mirrors refund-rule.json shape.
 */
public record EvalFixture(
        @JsonProperty("_comment") String comment,
        @JsonProperty("kbId") String kbId,
        @JsonProperty("version") long version,
        @JsonProperty("sourceUri") String sourceUri,
        @JsonProperty("permissionTags") List<String> permissionTags,
        @JsonProperty("document") EvalDocument document,
        @JsonProperty("query") EvalQuery query,
        @JsonProperty("expected") EvalExpected expected
) {
    public record EvalDocument(
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("kbId") String kbId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("title") String title,
            @JsonProperty("sections") List<EvalSection> sections
    ) {}
    public record EvalSection(
            @JsonProperty("heading") String heading,
            @JsonProperty("body") String body
    ) {}
    public record EvalQuery(
            @JsonProperty("userId") String userId,
            @JsonProperty("rawText") String rawText,
            @JsonProperty("permissionTags") List<String> permissionTags,
            @JsonProperty("topK") int topK
    ) {}
    public record EvalExpected(
            @JsonProperty("mustContainSubstring") String mustContainSubstring,
            @JsonProperty("mustContainSourceUri") String mustContainSourceUri,
            @JsonProperty("expectedChunkIds") List<String> expectedChunkIds
    ) {}
}
