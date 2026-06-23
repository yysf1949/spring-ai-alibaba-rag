package io.github.yysf1949.rag.agent.feedback.store;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import io.github.yysf1949.rag.agent.store.StoreAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2FeedbackRepository 集成测试 — 真实 H2 内存库 (非 mock).
 *
 * <p>走 {@link StoreAutoConfiguration#ensureAllSchema(JdbcTemplate)} 建表,
 * 跟线上启动路径一致. 覆盖 thumb / rating / messageId / kbVersion null 字段.</p>
 */
class H2FeedbackRepositoryTest {

    private static JdbcTemplate jdbc;
    private static H2FeedbackRepository repo;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:test_feedback_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        repo = new H2FeedbackRepository(jdbc);
    }

    @Test
    void saveAndFindByIdRoundTrips() {
        FeedbackRecord r = new FeedbackRecord(
                "FB-h2-001", "t1", "u1", "conv-123",
                "msg-001", Thumb.UP, 5, "excellent", "web", "v2",
                1700000000000L);
        repo.save(r);

        var found = repo.findById("t1", "FB-h2-001");
        assertThat(found).isPresent();
        assertThat(found.get().thumb()).isEqualTo(Thumb.UP);
        assertThat(found.get().rating()).isEqualTo(5);
        assertThat(found.get().comment()).isEqualTo("excellent");
        assertThat(found.get().messageId()).isEqualTo("msg-001");
        assertThat(found.get().kbVersion()).isEqualTo("v2");
        assertThat(found.get().sourceChannel()).isEqualTo("web");
    }

    @Test
    void saveAndFindByIdCrossTenantReturnsEmpty() {
        FeedbackRecord r = new FeedbackRecord(
                "FB-h2-002", "tenant-x", "u1", "conv-1",
                null, Thumb.DOWN, 1, null, "api", null,
                1700000001000L);
        repo.save(r);

        // 同 ID 不同 tenant 不可见
        assertThat(repo.findById("tenant-y", "FB-h2-002")).isEmpty();
        assertThat(repo.findById("tenant-x", "FB-h2-002")).isPresent();
    }

    @Test
    void saveHandlesNullOptionalFields() {
        // messageId / thumb / rating / comment / kbVersion 全可空
        FeedbackRecord r = new FeedbackRecord(
                "FB-h2-003", "t1", "u1", "conv-n",
                null, null, null, null, "api", null,
                1700000002000L);
        repo.save(r);

        var found = repo.findById("t1", "FB-h2-003");
        assertThat(found).isPresent();
        assertThat(found.get().thumb()).isNull();
        assertThat(found.get().rating()).isNull();
        assertThat(found.get().comment()).isNull();
        assertThat(found.get().messageId()).isNull();
        assertThat(found.get().kbVersion()).isNull();
    }

    @Test
    void findByConversationSortedAscending() {
        repo.save(new FeedbackRecord(
                "FB-h2-c1", "t1", "u1", "conv-c",
                null, Thumb.UP, null, null, "api", null,
                1700000010000L));
        repo.save(new FeedbackRecord(
                "FB-h2-c2", "t1", "u1", "conv-c",
                null, null, 3, null, "api", null,
                1700000005000L));
        repo.save(new FeedbackRecord(
                "FB-h2-c3", "t1", "u1", "conv-other",
                null, null, null, null, "api", null,
                1700000000000L));

        var found = repo.findByConversation("t1", "conv-c");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).feedbackId()).isEqualTo("FB-h2-c2");  // 500 先
        assertThat(found.get(1).feedbackId()).isEqualTo("FB-h2-c1");  // 1000 后
    }

    @Test
    void findByTenantRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            repo.save(new FeedbackRecord(
                    "FB-h2-L" + i, "tenant-big", "u", "c" + i,
                    null, Thumb.UP, null, null, "api", null,
                    1700000020000L + i));
        }
        var found = repo.findByTenant("tenant-big", 3);
        assertThat(found).hasSize(3);
    }

    @Test
    void countByTenantCorrect() {
        long before = repo.countByTenant("tenant-count");
        repo.save(new FeedbackRecord(
                "FB-h2-count1", "tenant-count", "u", "c",
                null, null, 5, null, "api", null, 1L));
        repo.save(new FeedbackRecord(
                "FB-h2-count2", "tenant-count", "u", "c",
                null, null, null, null, "api", null, 2L));
        assertThat(repo.countByTenant("tenant-count")).isEqualTo(before + 2);
    }

    // ---- Phase 40 T2: findByTenantRange ----

    @Test
    void findByTenantRangeWithBothBounds() {
        // 用 unique tenant 避免与其它测试共享 static @BeforeAll H2 实例.
        repo.save(new FeedbackRecord("FB-r1", "t2r1", "u", "c",
                null, Thumb.UP, null, null, "api", null, 1000L));
        repo.save(new FeedbackRecord("FB-r2", "t2r1", "u", "c",
                null, Thumb.UP, null, null, "api", null, 2000L));
        repo.save(new FeedbackRecord("FB-r3", "t2r1", "u", "c",
                null, Thumb.UP, null, null, "api", null, 3000L));

        var found = repo.findByTenantRange("t2r1", 1500L, 2500L, 100);
        assertThat(found).extracting(FeedbackRecord::feedbackId)
                .containsExactly("FB-r2");
    }

    @Test
    void findByTenantRangeNoBoundsReturnsAllSortedAsc() {
        repo.save(new FeedbackRecord("FB-n1", "t2n1", "u", "c",
                null, null, null, null, "api", null, 3000L));
        repo.save(new FeedbackRecord("FB-n2", "t2n1", "u", "c",
                null, null, null, null, "api", null, 1000L));
        repo.save(new FeedbackRecord("FB-n3", "t2n1", "u", "c",
                null, null, null, null, "api", null, 2000L));

        var found = repo.findByTenantRange("t2n1", null, null, 100);
        assertThat(found).extracting(FeedbackRecord::feedbackId)
                .containsExactly("FB-n2", "FB-n3", "FB-n1");
    }

    @Test
    void findByTenantRangeRespectsLimit() {
        // t-lim 已经被之前测试占用 — 改用 t2lim
        for (int i = 0; i < 10; i++) {
            repo.save(new FeedbackRecord("FB-L" + i, "t2lim", "u", "c",
                    null, null, null, null, "api", null, 5000L + i));
        }
        var found = repo.findByTenantRange("t2lim", null, null, 4);
        assertThat(found).hasSize(4);
        assertThat(found.get(0).feedbackId()).isEqualTo("FB-L0");
        assertThat(found.get(3).feedbackId()).isEqualTo("FB-L3");
    }
}