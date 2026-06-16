package io.github.yysf1949.rag.embedding.siliconflow;

import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.port.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * SiliconFlow LLM adapter (Phase 5-P4, spec §13.11).
 *
 * <p>Default model: {@code Qwen/Qwen2.5-7B-Instruct} — lowest-cost tier on
 * SiliconFlow at the time of writing. Swap via
 * {@code rag.siliconflow.llm.model}.</p>
 *
 * <h2>Error contract (per {@link LlmService})</h2>
 * <ul>
 *   <li>Never returns {@code null}. Empty string on terminal failure.</li>
 *   <li>Throws {@link LlmUnavailableException} on transient upstream
 *       failures after retries exhausted. {@code QAService} catches this
 *       and falls back to {@code FALLBACK_RULE}.</li>
 *   <li>Retries: up to {@code maxRetries} on 5xx / timeout. 4xx is terminal.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2> Stateless after construction.
 */
public class SiliconFlowLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowLlmService.class);

    private final Duration backoffMin;
    private final Duration backoffMax;

    private final WebClient webClient;
    private final SiliconFlowProperties properties;

    public SiliconFlowLlmService(WebClient webClient, SiliconFlowProperties properties) {
        this(webClient, properties, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    /** Test-friendly constructor — short backoff so hermetic tests don't sleep. */
    public SiliconFlowLlmService(WebClient webClient, SiliconFlowProperties properties,
                                 Duration backoffMin, Duration backoffMax) {
        this.webClient = webClient;
        this.properties = properties;
        this.backoffMin = backoffMin;
        this.backoffMax = backoffMax;
    }

    @Override
    public String modelId() {
        return properties.getLlm().getModel();
    }

    @Override
    public String generateAnswer(String tenantId, String prompt) {
        ChatRequest body = new ChatRequest();
        body.model = properties.getLlm().getModel();
        body.messages = List.of(
                new ChatMessage("user", prompt == null ? "" : prompt)
        );
        body.max_tokens = properties.getLlm().getMaxTokens();
        body.temperature = properties.getLlm().getTemperature();
        body.stream = false;

        int maxRetries = Math.max(0, properties.getLlm().getMaxRetries());
        int timeoutSeconds = Math.max(1, properties.getLlm().getTimeoutSeconds());

        try {
            ChatResponse resp = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ChatResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, backoffMin)
                            .maxBackoff(backoffMax)
                            .filter(SiliconFlowLlmService::isTransient))
                    .onErrorResume(this::mapToLlmUnavailable)
                    .block();

            if (resp == null || resp.choices == null || resp.choices.isEmpty()) {
                throw new LlmUnavailableException("SiliconFlow LLM returned empty response");
            }
            String content = resp.choices.get(0).message == null ? null : resp.choices.get(0).message.content;
            if (content == null) {
                throw new LlmUnavailableException("SiliconFlow LLM returned null content");
            }
            if (tenantId != null) {
                log.debug("SiliconFlowLlmService.tenant={} model={} promptChars={} responseChars={}",
                        tenantId, body.model, prompt == null ? 0 : prompt.length(), content.length());
            }
            return content;
        } catch (LlmUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new LlmUnavailableException("SiliconFlow LLM call failed: " + ex.getMessage(), ex);
        }
    }

    private Mono<ChatResponse> mapToLlmUnavailable(Throwable ex) {
        if (ex instanceof LlmUnavailableException lu) {
            return Mono.error(lu);
        }
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        log.warn("SiliconFlow LLM call failed (final attempt): {}", msg);
        return Mono.error(new LlmUnavailableException("SiliconFlow LLM: " + msg, ex));
    }

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        return true;
    }

    // ─── DTOs (OpenAI-compatible /v1/chat/completions) ───────────────

    static class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public int max_tokens;
        public double temperature;
        public boolean stream;
    }

    static class ChatMessage {
        public String role;
        public String content;
        public ChatMessage() {}
        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static class ChatResponse {
        public String id;
        public String model;
        public List<ChatChoice> choices;
    }

    static class ChatChoice {
        public int index;
        public ChatMessage message;
        public String finish_reason;
    }
}