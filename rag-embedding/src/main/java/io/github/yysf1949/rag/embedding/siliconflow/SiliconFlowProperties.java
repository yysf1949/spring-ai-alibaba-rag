package io.github.yysf1949.rag.embedding.siliconflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SiliconFlow adapters (Phase 5-P4).
 *
 * <p>Backed by {@code rag.siliconflow.*} properties in
 * {@code rag-app/src/main/resources/application.yml}. All sensitive
 * fields ({@link #apiKey}) <b>must</b> be supplied via env var
 * {@code SILICONFLOW_API_KEY} — the YAML key is a placeholder.</p>
 *
 * <p>The default model trio is:
 * <ul>
 *   <li>Embedding: {@code BAAI/bge-m3} (1024-dim, bge-reranker compatible)</li>
 *   <li>Rerank: {@code BAAI/bge-reranker-v2-m3}</li>
 *   <li>LLM: {@code Qwen/Qwen2.5-7B-Instruct} (lowest-cost tier on SiliconFlow)</li>
 * </ul>
 *
 * <p>See {@code rag-embedding/.env.example} for overrides.</p>
 */
@ConfigurationProperties(prefix = "rag.siliconflow")
public class SiliconFlowProperties {

    /** Master switch — when false the stub adapters are used instead. */
    private boolean enabled = false;

    /** Bearer token. Wire via env var {@code SILICONFLOW_API_KEY}. */
    private String apiKey = "";

    /** API base URL — SiliconFlow is OpenAI-compatible. */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    private final Embedding embedding = new Embedding();
    private final Rerank rerank = new Rerank();
    private final Llm llm = new Llm();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Embedding getEmbedding() { return embedding; }
    public Rerank getRerank() { return rerank; }
    public Llm getLlm() { return llm; }

    /**
     * @return true only if the adapter is fully wired (enabled + non-blank key).
     *         Used by the {@code @ConditionalOnExpression} on the auto-config
     *         to avoid constructing half-configured beans.
     */
    public boolean isActive() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public static class Embedding {
        private String model = "BAAI/bge-m3";
        private int dimension = 1024;
        private int timeoutSeconds = 30;
        private int maxRetries = 2;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Rerank {
        private String model = "BAAI/bge-reranker-v2-m3";
        private int timeoutSeconds = 30;
        private int maxRetries = 1;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Llm {
        private String model = "Qwen/Qwen2.5-7B-Instruct";
        private int timeoutSeconds = 60;
        private int maxRetries = 1;
        private int maxTokens = 2048;
        private double temperature = 0.2;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }
}