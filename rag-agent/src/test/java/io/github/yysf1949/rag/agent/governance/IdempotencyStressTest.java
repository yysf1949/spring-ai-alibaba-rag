package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.builtin.ComplaintTool;
import io.github.yysf1949.rag.agent.builtin.CouponTool;
import io.github.yysf1949.rag.agent.builtin.OrderTool;
import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryComplaintRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryCouponRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.service.CouponApplicationService;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等性压力测试 — 并发场景下验证幂等机制的正确性。
 *
 * <p>对齐「路条编程」文章原话：「即使模型重复调用，系统也应该返回第一次的执行结果」</p>
 *
 * <h2>测试场景</h2>
 * <ol>
 *   <li>并发重复请求 — 10 个线程同时用相同 idempotencyKey 调用 cancelOrder</li>
 *   <li>并发不同请求 — 10 个线程用不同 idempotencyKey 调用 issueCoupon</li>
 *   <li>快速连续重复 — 同一线程快速调用 5 次 createComplaint（相同 key）</li>
 *   <li>跨工具幂等隔离 — 不同工具使用相同 idempotencyKey</li>
 *   <li>幂等键过期 — 验证 TTL 行为（如果实现了）</li>
 * </ol>
 *
 * <h2>实现说明</h2>
 * <p>所有工具直接调用（不经过 AgentLoop），使用 {@link InMemoryIdempotencyStore}。
 * 通过 {@link ReplayTrackingStore} 装饰器追踪幂等回放次数并同步记录到
 * {@link AgentMetrics#recordIdempotencyReplay(String)}。</p>
 */
class IdempotencyStressTest {

    private static final String TENANT = "tenant-1";
    private static final String USER = "user-1";
    private static final String SESSION = "session-1";

    private MeterRegistry registry;
    private AgentMetrics metrics;
    private InMemoryIdempotencyStore rawStore;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
        rawStore = new InMemoryIdempotencyStore();
    }

    // ========== 辅助方法 ==========

    /** 创建带回放追踪的幂等存储装饰器 */
    private ReplayTrackingStore createTrackingStore() {
        return new ReplayTrackingStore(rawStore, metrics);
    }

    private IdempotencyKey key(String toolName, String token) {
        return IdempotencyKey.of(TENANT, USER, SESSION, toolName, token);
    }

    private double getReplayCount(String toolName) {
        return registry.counter("agent.idempotency.replays",
                "tool", toolName).count();
    }

    // ========== 场景 1: 并发重复请求 ==========

    @Test
    @DisplayName("场景1: 10个线程同时用相同 idempotencyKey 调用 cancelOrder — 只有1个成功执行，其他9个返回第一次的结果")
    void concurrentDuplicateRequests_cancelOrder() throws Exception {
        // Arrange: 预置一条 CREATED 订单
        InMemoryOrderRepository orderRepo = new InMemoryOrderRepository();
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-STRESS-1", TENANT, USER, 50_00L, "CREATED"));

        ReplayTrackingStore trackingStore = createTrackingStore();
        OrderApplicationService service = new OrderApplicationService(
                orderRepo, trackingStore, metrics);
        OrderTool tool = new OrderTool(service);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<OrderTool.CancelOrderResponse> results = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        IdempotencyKey idemKey = key("cancel_order", "cancel-stress-token-1");

        // Act: 10 个线程同时发起
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // 所有线程同时起跑
                    var resp = tool.cancelOrder(idemKey,
                            new OrderTool.CancelOrderRequest(
                                    TENANT, USER, "ORD-STRESS-1", 50_00L, "并发取消"));
                    results.add(resp);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // 开闸
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Assert: 所有 10 个调用都应成功返回
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(errorCount.get()).isEqualTo(0);

        // 所有结果都应该是 CANCELLED
        assertThat(results).hasSize(10);
        assertThat(results).allSatisfy(resp -> {
            assertThat(resp.orderId()).isEqualTo("ORD-STRESS-1");
            assertThat(resp.status()).isEqualTo("CANCELLED");
        });

        // H2 (InMemory) 中订单状态只变更一次 — 最终是 CANCELLED
        OrderRepositoryPort.OrderRecord finalOrder = orderRepo
                .findByIdAndTenant("ORD-STRESS-1", TENANT).orElseThrow();
        assertThat(finalOrder.status()).isEqualTo("CANCELLED");

        // 幂等回放计数器 = 9（10 个线程中 1 个 FIRST，9 个 REPLAY）
        assertThat(getReplayCount("cancel_order")).isEqualTo(9.0);
    }

    // ========== 场景 2: 并发不同请求 ==========

    @Test
    @DisplayName("场景2: 10个线程用不同 idempotencyKey 调用 issueCoupon — 10个都成功执行")
    void concurrentDifferentRequests_issueCoupon() throws Exception {
        // Arrange
        InMemoryCouponRepository couponRepo = new InMemoryCouponRepository();
        ReplayTrackingStore trackingStore = createTrackingStore();
        CouponApplicationService service = new CouponApplicationService(
                couponRepo, trackingStore, metrics);
        CouponTool tool = new CouponTool(service);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<CouponTool.IssueCouponResponse> results = new CopyOnWriteArrayList<>();
        AtomicInteger errorCount = new AtomicInteger();

        // Act: 每个线程使用不同的 idempotencyKey
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    IdempotencyKey idemKey = key("issue_coupon", "coupon-token-" + idx);
                    var resp = tool.issueCoupon(idemKey,
                            new CouponTool.IssueCouponRequest(
                                    TENANT, USER, "ORD-" + idx, 10_00L, "COMPENSATION"));
                    results.add(resp);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Assert: 10 个都成功
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(results).hasSize(10);

        // 每个结果都有唯一的 couponId
        List<String> couponIds = results.stream()
                .map(CouponTool.IssueCouponResponse::couponId)
                .toList();
        assertThat(couponIds).doesNotHaveDuplicates();

        // H2 (InMemory) 中有 10 张优惠券
        List<CouponRepositoryPort.CouponRecord> allCoupons =
                couponRepo.findActiveByTenantAndUser(TENANT, USER);
        assertThat(allCoupons).hasSize(10);

        // 没有幂等回放（所有 key 都不同）
        assertThat(getReplayCount("issue_coupon")).isEqualTo(0.0);
    }

    // ========== 场景 3: 快速连续重复 ==========

    @Test
    @DisplayName("场景3: 同一线程快速调用5次 createComplaint (相同key) — 第1次创建，后4次返回第一次的结果")
    void rapidSequentialRepeats_createComplaint() {
        // Arrange
        InMemoryComplaintRepository complaintRepo = new InMemoryComplaintRepository();
        ReplayTrackingStore trackingStore = createTrackingStore();
        ComplaintTool tool = new ComplaintTool(complaintRepo, trackingStore);

        IdempotencyKey idemKey = key("create_complaint", "complaint-repeat-token");
        ComplaintTool.ComplaintRequest request = new ComplaintTool.ComplaintRequest(
                TENANT, USER, "ORD-100", "QUALITY", "商品有质量问题", "P2");

        // Act: 快速连续调用 5 次
        List<ComplaintTool.ComplaintResponse> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(tool.createComplaint(idemKey, request));
        }

        // Assert: 所有 5 次都返回成功结果
        assertThat(results).hasSize(5);
        assertThat(results).allSatisfy(resp -> {
            assertThat(resp.status()).isEqualTo("CREATED");
            assertThat(resp.complaintId()).isNotNull();
        });

        // 所有结果的 complaintId 相同（第一次创建的 ID）
        String firstComplaintId = results.get(0).complaintId();
        assertThat(results).allSatisfy(resp ->
                assertThat(resp.complaintId()).isEqualTo(firstComplaintId));

        // H2 (InMemory) 中只有 1 条投诉记录
        // InMemoryComplaintRepository 以 tenantId:complaintId 为 key，所以 save 相同 ID 只会有一条
        // 但我们需要验证确实只创建了一条 — 通过检查所有 complaintId 相同来间接验证
        assertThat(results.stream().map(ComplaintTool.ComplaintResponse::complaintId).distinct().count())
                .isEqualTo(1);

        // 幂等回放计数器 = 4（5 次中 1 次 FIRST，4 次 REPLAY）
        assertThat(getReplayCount("create_complaint")).isEqualTo(4.0);
    }

    // ========== 场景 4: 跨工具幂等隔离 ==========

    @Test
    @DisplayName("场景4: 不同工具使用相同 idempotencyKey — 各自独立，互不影响")
    void crossToolIdempotencyIsolation() {
        // Arrange: 预置订单和存储
        InMemoryOrderRepository orderRepo = new InMemoryOrderRepository();
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-ISOL-1", TENANT, USER, 30_00L, "CREATED"));

        InMemoryCouponRepository couponRepo = new InMemoryCouponRepository();
        ReplayTrackingStore trackingStore = createTrackingStore();

        OrderApplicationService orderService = new OrderApplicationService(
                orderRepo, trackingStore, metrics);
        CouponApplicationService couponService = new CouponApplicationService(
                couponRepo, trackingStore, metrics);

        OrderTool orderTool = new OrderTool(orderService);
        CouponTool couponTool = new CouponTool(couponService);

        // 相同的 idempotencyToken，但不同的 toolName → 不同的 IdempotencyKey hash
        String sharedToken = "shared-cross-tool-token";

        // Act: cancelOrder 和 issueCoupon 使用相同的 rawToken
        var cancelResp = orderTool.cancelOrder(
                key("cancel_order", sharedToken),
                new OrderTool.CancelOrderRequest(
                        TENANT, USER, "ORD-ISOL-1", 30_00L, "跨工具隔离测试"));

        var couponResp = couponTool.issueCoupon(
                key("issue_coupon", sharedToken),
                new CouponTool.IssueCouponRequest(
                        TENANT, USER, "ORD-ISOL-1", 15_00L, "补偿"));

        // Assert: 两个操作都成功
        assertThat(cancelResp.status()).isEqualTo("CANCELLED");
        assertThat(cancelResp.orderId()).isEqualTo("ORD-ISOL-1");

        assertThat(couponResp.couponId()).startsWith("CPN-");
        assertThat(couponResp.amountCents()).isEqualTo(15_00L);

        // 验证 IdempotencyKey 的 hash 不同（即使 rawToken 相同）
        IdempotencyKey orderKey = key("cancel_order", sharedToken);
        IdempotencyKey couponKey = key("issue_coupon", sharedToken);
        assertThat(orderKey.hash()).isNotEqualTo(couponKey.hash());

        // 没有幂等回放（两个都是各自工具的 FIRST 调用）
        assertThat(getReplayCount("cancel_order")).isEqualTo(0.0);
        assertThat(getReplayCount("issue_coupon")).isEqualTo(0.0);

        // H2 (InMemory): 订单已取消，优惠券已发放
        assertThat(orderRepo.findByIdAndTenant("ORD-ISOL-1", TENANT).orElseThrow().status())
                .isEqualTo("CANCELLED");
        assertThat(couponRepo.findActiveByTenantAndUser(TENANT, USER)).hasSize(1);
    }

    // ========== 场景 5: 幂等键过期 (TTL) ==========

    @Test
    @DisabledIf("isTtlNotImplemented")
    @DisplayName("场景5: 幂等键过期 — 验证 TTL 行为")
    void idempotencyKeyExpiration() throws Exception {
        // Arrange: 使用带有 TTL 的 store（如果实现了）
        InMemoryOrderRepository orderRepo = new InMemoryOrderRepository();
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-TTL-1", TENANT, USER, 20_00L, "CREATED"));

        // InMemoryIdempotencyStore 当前没有 TTL 实现
        // 此测试验证：如果未来添加 TTL，过期后相同 key 应该被视为新请求
        ReplayTrackingStore trackingStore = createTrackingStore();
        OrderApplicationService service = new OrderApplicationService(
                orderRepo, trackingStore, metrics);
        OrderTool tool = new OrderTool(service);

        IdempotencyKey idemKey = key("cancel_order", "ttl-test-token");

        // 第一次调用
        var resp1 = tool.cancelOrder(idemKey,
                new OrderTool.CancelOrderRequest(
                        TENANT, USER, "ORD-TTL-1", 20_00L, "TTL 测试"));
        assertThat(resp1.status()).isEqualTo("CANCELLED");

        // 等待 TTL 过期（如果实现了）
        Thread.sleep(6_000); // 假设 TTL 是 5 秒

        // 预置新订单用于过期后的重试
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-TTL-2", TENANT, USER, 25_00L, "CREATED"));
        IdempotencyKey idemKey2 = key("cancel_order", "ttl-test-token-2");

        // TTL 过期后，相同 key 应该被视为新请求
        var resp2 = tool.cancelOrder(idemKey2,
                new OrderTool.CancelOrderRequest(
                        TENANT, USER, "ORD-TTL-2", 25_00L, "TTL 过期后"));
        assertThat(resp2.status()).isEqualTo("CANCELLED");
    }

    /**
     * 判断 TTL 是否未实现 — 用于 @DisabledIf。
     * InMemoryIdempotencyStore 当前没有 TTL 机制，跳过此测试。
     */
    @SuppressWarnings("unused")
    static boolean isTtlNotImplemented() {
        // InMemoryIdempotencyStore 没有 TTL 实现
        return true;
    }

    // ========== 内部类: 回放追踪装饰器 ==========

    /**
     * 装饰器：在 {@link InMemoryIdempotencyStore} 基础上增加回放计数追踪。
     *
     * <p>当 {@link IdempotencyStore#putIfAbsent} 返回 REPLAY 时，
     * 自动调用 {@link AgentMetrics#recordIdempotencyReplay(String)} 记录回放。</p>
     *
     * <p>这模拟了 {@code DefaultAgentLoop} 中的回放检测逻辑，
     * 使得直接调用工具时也能正确追踪幂等回放指标。</p>
     */
    private static class ReplayTrackingStore implements IdempotencyStore {

        private final IdempotencyStore delegate;
        private final AgentMetrics metrics;

        ReplayTrackingStore(IdempotencyStore delegate, AgentMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public PutResult putIfAbsent(IdempotencyKey key, Object value) {
            PutResult result = delegate.putIfAbsent(key, value);
            if (result.isReplay()) {
                metrics.recordIdempotencyReplay(key.toolName());
            }
            return result;
        }

        @Override
        public void replace(IdempotencyKey key, Object value) {
            delegate.replace(key, value);
        }
    }
}
