package io.github.yysf1949.rag.embedding.stub;

import io.github.yysf1949.rag.core.port.LlmService;

/**
 * Stub LLM — echoes the prompt wrapped in a marker. Marks itself with
 * {@code modelId() == "stub-llm"} so dashboards can split "stub
 * traffic" from "real LLM traffic" if both are ever live.
 *
 * <p>Real impl: {@code io.github.yysf1949.rag.embedding.dashscope.DashScopeLlmService}
 * (Phase 5-P4, spec §13.11).</p>
 *
 * <p><b>Never throws</b> per the {@link LlmService} error contract — the
 * degradation ladder in {@code QAServiceImpl} relies on the stub LLM
 * behaving like a working LLM so all other branches can be exercised.</p>
 */
public class StubLlmService implements LlmService {

    @Override
    public String generateAnswer(String tenantId, String prompt) {
        return "[stub-llm] Received prompt of length " + prompt.length()
                + " chars for tenant " + tenantId
                + "; would normally call DashScope qwen-plus here.";
    }

    @Override
    public String modelId() {
        return "stub-llm";
    }
}