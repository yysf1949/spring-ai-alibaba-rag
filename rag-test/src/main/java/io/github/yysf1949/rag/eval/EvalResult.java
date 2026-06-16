package io.github.yysf1949.rag.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EvalResult(
        @JsonProperty("fixtureName") String fixtureName,
        @JsonProperty("recallAtK") double recallAtK,
        @JsonProperty("mrr") double mrr,
        @JsonProperty("groundedRate") double groundedRate,
        @JsonProperty("pass") boolean pass
) {}
