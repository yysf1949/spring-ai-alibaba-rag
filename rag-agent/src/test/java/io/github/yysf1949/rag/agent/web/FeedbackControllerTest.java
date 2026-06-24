package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FeedbackController} 测试 — MockMvc standalone 模式.
 *
 * <p>覆盖: POST/GET/list + 跨租户 404 + thumb/rating/comment 校验.
 * 走 standaloneSetup 不启动 Spring 上下文, 跟 KbVersionControllerTest 风格一致.</p>
 */
class FeedbackControllerTest {

    private FeedbackPort port;
    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        port = mock(FeedbackPort.class);
        // 注册 AgentExceptionHandler 让 FeedbackValidationException → 400 映射生效.
        // standaloneSetup 不会自动扫 @RestControllerAdvice, 必须显式 setControllerAdvice.
        mvc = MockMvcBuilders.standaloneSetup(new FeedbackController(port))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }

    @Test
    void submitFeedbackWithThumbReturnsRecord() throws Exception {
        when(port.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {
                  "userId": "u1",
                  "conversationId": "conv-1",
                  "messageId": "msg-1",
                  "thumb": "UP",
                  "sourceChannel": "web"
                }
                """;

        mvc.perform(post("/api/agent/feedback")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumb").value("UP"))
                .andExpect(jsonPath("$.tenantId").value("t1"))
                .andExpect(jsonPath("$.userId").value("u1"));
    }

    @Test
    void submitFeedbackWithRatingAndComment() throws Exception {
        when(port.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {
                  "userId": "u2",
                  "conversationId": "conv-2",
                  "rating": 5,
                  "comment": "great service",
                  "kbVersion": "v1.0"
                }
                """;

        mvc.perform(post("/api/agent/feedback")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("great service"))
                .andExpect(jsonPath("$.kbVersion").value("v1.0"));
    }

    @Test
    void submitFeedbackWithNoSignalsReturns400() throws Exception {
        // thumb=null, rating=null, comment=null → 全部空 → 400
        String body = """
                {
                  "userId": "u1",
                  "conversationId": "conv-1"
                }
                """;

        mvc.perform(post("/api/agent/feedback")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFeedbackCrossTenantReturns404() throws Exception {
        when(port.findById(eq("tenant-b"), eq("FB-001"))).thenReturn(Optional.empty());

        mvc.perform(get("/api/agent/feedback/{id}", "FB-001")
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getFeedbackSameTenantReturns200() throws Exception {
        FeedbackRecord r = new FeedbackRecord(
                "FB-001", "t1", "u1", "conv-1",
                "msg-1", Thumb.UP, 5, "ok", "web", "v1", 1L);
        when(port.findById("t1", "FB-001")).thenReturn(Optional.of(r));

        mvc.perform(get("/api/agent/feedback/{id}", "FB-001")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackId").value("FB-001"));
    }

    @Test
    void listFeedbackReturnsByTenant() throws Exception {
        FeedbackRecord r1 = new FeedbackRecord(
                "FB-a", "t1", "u1", "c1",
                null, Thumb.UP, null, null, "api", null, 1L);
        FeedbackRecord r2 = new FeedbackRecord(
                "FB-b", "t1", "u2", "c2",
                null, null, 3, "ok", "api", null, 2L);
        when(port.findByTenant(eq("t1"), eq(100))).thenReturn(List.of(r1, r2));

        mvc.perform(get("/api/agent/feedback")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feedbackId").value("FB-a"))
                .andExpect(jsonPath("$[1].feedbackId").value("FB-b"));
    }

    @Test
    void listByConversationFiltersByTenant() throws Exception {
        FeedbackRecord r = new FeedbackRecord(
                "FB-c", "t1", "u1", "conv-x",
                null, Thumb.DOWN, 1, null, "api", null, 1L);
        when(port.findByConversation("t1", "conv-x")).thenReturn(List.of(r));

        mvc.perform(get("/api/agent/feedback/conversation/{conv}", "conv-x")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feedbackId").value("FB-c"));
    }
}