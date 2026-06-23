package io.github.yysf1949.rag.agent.feedback.store;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryFeedbackRepository 单元测试 — 不依赖 Spring / DB.
 *
 * <p>覆盖：save/findById (含跨租户隔离)/findByConversation/findByTenant/countByTenant.
 * Field validation 由 {@link FeedbackRecord} 构造器保证.</p>
 */
class InMemoryFeedbackRepositoryTest {

    private InMemoryFeedbackRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryFeedbackRepository();
    }

    @Test
    void saveAndFindByIdReturnsRecord() {
        FeedbackRecord r = new FeedbackRecord(
                "FB-001", "tenant-a", "user-1", "conv-1",
                "msg-1", Thumb.UP, 5, "great!", "web", "v1",
                1700000000000L);
        repo.save(r);

        assertThat(repo.findById("tenant-a", "FB-001"))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.tenantId()).isEqualTo("tenant-a");
                    assertThat(found.thumb()).isEqualTo(Thumb.UP);
                    assertThat(found.rating()).isEqualTo(5);
                    assertThat(found.comment()).isEqualTo("great!");
                });
    }

    @Test
    void findByIdCrossTenantReturnsEmpty() {
        FeedbackRecord r = new FeedbackRecord(
                "FB-002", "tenant-a", "u1", "conv-1",
                null, Thumb.DOWN, 1, null, "api", null,
                1700000000000L);
        repo.save(r);

        // tenant-b 不能看见 tenant-a 的数据
        assertThat(repo.findById("tenant-b", "FB-002")).isEmpty();
        assertThat(repo.findById("tenant-a", "FB-002")).isPresent();
    }

    @Test
    void findByConversationReturnsSortedByCreatedAt() {
        repo.save(new FeedbackRecord(
                "FB-010", "t1", "u1", "conv-x",
                null, Thumb.UP, null, null, "api", null,
                1700000000300L));
        repo.save(new FeedbackRecord(
                "FB-011", "t1", "u1", "conv-x",
                null, null, 3, "ok", "api", null,
                1700000000100L));
        repo.save(new FeedbackRecord(
                "FB-012", "t1", "u1", "conv-y",  // 不同的会话
                null, null, null, null, "api", null,
                1700000000200L));

        var found = repo.findByConversation("t1", "conv-x");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).feedbackId()).isEqualTo("FB-011");  // 100 先
        assertThat(found.get(1).feedbackId()).isEqualTo("FB-010");  // 300 后
    }

    @Test
    void findByTenantRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            repo.save(new FeedbackRecord(
                    "FB-T" + i, "t1", "u1", "conv-" + i,
                    null, Thumb.UP, null, null, "api", null,
                    1700000000000L + i));
        }
        repo.save(new FeedbackRecord(
                "FB-other", "tenant-b", "u2", "conv-other",
                null, Thumb.DOWN, null, null, "api", null,
                1700000000000L));

        var found = repo.findByTenant("t1", 3);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).feedbackId()).isEqualTo("FB-T0");  // 最早的
        assertThat(found.get(2).feedbackId()).isEqualTo("FB-T2");
        assertThat(found).noneMatch(r -> r.tenantId().equals("tenant-b"));
    }

    @Test
    void countByTenantIsolatesByTenant() {
        repo.save(new FeedbackRecord(
                "FB-a1", "tenant-a", "u1", "c1",
                null, Thumb.UP, null, null, "api", null, 1L));
        repo.save(new FeedbackRecord(
                "FB-a2", "tenant-a", "u2", "c2",
                null, null, null, null, "api", null, 2L));
        repo.save(new FeedbackRecord(
                "FB-b1", "tenant-b", "u3", "c3",
                null, null, null, null, "api", null, 3L));

        assertThat(repo.countByTenant("tenant-a")).isEqualTo(2L);
        assertThat(repo.countByTenant("tenant-b")).isEqualTo(1L);
        assertThat(repo.countByTenant("tenant-c")).isZero();
    }

    @Test
    void recordValidationRejectsBadRating() {
        assertThatThrownBy(() -> new FeedbackRecord(
                "FB-bad", "t1", "u1", "c1",
                null, null, 0, null, "api", null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating");

        assertThatThrownBy(() -> new FeedbackRecord(
                "FB-bad", "t1", "u1", "c1",
                null, null, 6, null, "api", null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}