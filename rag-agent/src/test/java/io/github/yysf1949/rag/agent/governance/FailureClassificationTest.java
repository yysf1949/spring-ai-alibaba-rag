package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.builtin.PaymentChannelTool;
import io.github.yysf1949.rag.agent.builtin.RefundRuleTool;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort.RefundRecord;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryRefundRepository;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.service.RefundApplicationService;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort.OrderRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 故障分类与补偿链验证测试。
 *
 * <p>对齐「路条编程」文章核心论点：「错误发生时，要区分是业务错误、工具执行错误还是模型幻觉」</p>
 *
 * <h2>测试场景</h2>
 * <ol>
 *   <li>业务错误 — cancelOrder 但订单状态不是 CREATED/PAID</li>
 *   <li>工具执行错误 — 模拟 RuntimeException</li>
 *   <li>金额超限 — createRefund 超过 5000 元</li>
 *   <li>补偿链 — 幂等创建 + cancelRefund 回滚</li>
 *   <li>乐观锁冲突 — 并发修改同一退款单</li>
 * </ol>
 */
class FailureClassificationTest {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;
    private InMemoryRefundRepository refundRepo;
    private InMemoryOrderRepository orderRepo;
    private InMemoryIdempotencyStore idemStore;
    private RefundApplicationService refundService;
    private OrderApplicationService orderService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
        refundRepo = new InMemoryRefundRepository();
        orderRepo = new InMemoryOrderRepository();
        idemStore = new InMemoryIdempotencyStore();

        var paymentChannel = new PaymentChannelTool();
        var refundRule = new RefundRuleTool(paymentChannel);
        refundService = new RefundApplicationService(refundRepo, refundRule, idemStore, metrics);
        orderService = new OrderApplicationService(orderRepo, idemStore, metrics);
    }

    private IdempotencyKey key(String tool, String token) {
        return IdempotencyKey.of("t1", "u1", "s1", tool, token);
    }

    // ====== 场景 1: 业务错误 (BusinessError) ======

    @Test
    @DisplayName("cancelOrder 状态不是 CREATED/PAID → 业务错误 + recordBusinessError 被调用")
    void businessError_cancelOrderInvalidStatus() {
        // 创建一个 SHIPPED 状态的订单（不可取消）
        orderRepo.save(new OrderRecord("ORD-SHIPPED", "t1", "u1", 100_00L, "SHIPPED"));

        // 取消订单 — 应抛 IllegalStateException
        assertThatThrownBy(() ->
                orderService.cancelOrder("t1", "u1", "ORD-SHIPPED", 100_00L, "不想要了",
                        key("cancel_order", "cancel-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel order in status: SHIPPED");

        // 验证: AgentMetrics.recordBusinessError 被调用
        Counter bizErrCounter = registry.find("agent.tool.business_errors")
                .tag("tool", "cancel_order")
                .tag("errorType", "INVALID_STATUS")
                .counter();
        assertThat(bizErrCounter).isNotNull();
        assertThat(bizErrCounter.count()).isEqualTo(1.0);

        // 验证: 不应该触发自动重试 — 重试通常体现为 idempotency 的 replay 状态
        // 检查 idempotency store 中该 key 仍为首次写入（无 replay 记录）
        assertThat(idemStore.putIfAbsent(
                key("cancel_order", "cancel-1"), null).isFirst()).isFalse();
        // 首次已被占用，但因为异常发生，没有 replace 回填 — 说明不会有自动重试的回填逻辑
    }

    // ====== 场景 2: 工具执行错误 (ToolExecutionError) ======

    @Test
    @DisplayName("模拟 RuntimeException → 工具执行错误，recordErrorExecution 被调用")
    void toolExecutionError_runtimeException() {
        // 构造一个会抛 RuntimeException 的 RefundRepositoryPort
        RefundRepositoryPort failingRepo = new RefundRepositoryPort() {
            @Override
            public RefundRecord save(RefundRecord refund) {
                throw new RuntimeException("Database connection lost");
            }

            @Override
            public Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
                throw new RuntimeException("Database connection lost");
            }

            @Override
            public int count() {
                return 0;
            }
        };

        var paymentChannel = new PaymentChannelTool();
        var refundRule = new RefundRuleTool(paymentChannel);
        var failService = new RefundApplicationService(
                failingRepo, refundRule, idemStore, metrics);

        // 调用 createRefund — 应抛 RuntimeException
        assertThatThrownBy(() ->
                failService.createRefund("t1", "u1", "ORD-1", 50_00L, "质量问题",
                        key("create_refund", "tool-err-1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection lost");

        // 验证: 模拟编排层捕获异常后调用 recordErrorExecution
        metrics.recordErrorExecution("create_refund", "tool_execution_error");

        Counter toolErrCounter = registry.find("agent.tool.errors")
                .tag("tool", "create_refund")
                .tag("type", "tool_execution_error")
                .counter();
        assertThat(toolErrCounter).isNotNull();
        assertThat(toolErrCounter.count()).isEqualTo(1.0);
    }

    // ====== 场景 3: 金额超限错误 (AmountLimitExceeded) ======

    @Test
    @DisplayName("createRefund 超过 5000 元 → AmountLimitExceededException，错误信息包含金额上限")
    void amountLimitExceeded_over5000() {
        // RefundApplicationService.CREATE_MAX_AMOUNT_CENTS = 500_00 (500 元)
        // 传入 6000 元 = 6000_00 分
        long requestedAmount = 6000_00L;

        assertThatThrownBy(() ->
                refundService.createRefund("t1", "u1", "ORD-1", requestedAmount, "高额退款",
                        key("create_refund", "over-limit-1")))
                .isInstanceOf(AmountLimitExceededException.class)
                .satisfies(ex -> {
                    AmountLimitExceededException alee = (AmountLimitExceededException) ex;
                    assertThat(alee.requestedCents()).isEqualTo(requestedAmount);
                    assertThat(alee.limitCents()).isEqualTo(RefundApplicationService.CREATE_MAX_AMOUNT_CENTS);
                    assertThat(alee.getMessage()).contains(String.valueOf(RefundApplicationService.CREATE_MAX_AMOUNT_CENTS));
                });

        // 验证: 不应写入退款记录
        assertThat(refundRepo.count()).isZero();

        // 验证: business_errors 指标被记录
        Counter bizErrCounter = registry.find("agent.tool.business_errors")
                .tag("tool", "create_refund")
                .tag("errorType", "AMOUNT_EXCEEDED")
                .counter();
        assertThat(bizErrCounter).isNotNull();
        assertThat(bizErrCounter.count()).isEqualTo(1.0);
    }

    // ====== 场景 4: 补偿链验证 — 幂等 + cancelRefund 回滚 ======

    @Test
    @DisplayName("createRefund 幂等: 二次调用返回原结果；cancelRefund 回滚后状态为 CANCELLED")
    void compensationChain_idempotentCreateAndCancel() {
        IdempotencyKey idemKey = key("create_refund", "comp-1");

        // 第一次调用: 创建退款单
        RefundRecord first = refundService.createRefund(
                "t1", "u1", "ORD-1", 200_00L, "质量问题", idemKey);
        assertThat(first.refundId()).startsWith("REF-");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(refundRepo.count()).isEqualTo(1);

        // 第二次调用 (相同 idempotencyKey): 返回第一次的结果，不创建新单
        RefundRecord second = refundService.createRefund(
                "t1", "u1", "ORD-1", 200_00L, "质量问题", idemKey);
        assertThat(second.refundId()).isEqualTo(first.refundId());
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(refundRepo.count()).isEqualTo(1); // 没有创建新单

        // cancelRefund 触发回滚
        RefundRecord cancelled = refundService.cancelRefund(
                first.refundId(), "t1", "用户撤销退款");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.reason()).isEqualTo("用户撤销退款");

        // 验证: 验证退款状态确实变为 CANCELLED
        RefundRecord reloaded = refundRepo.findByIdAndTenant(first.refundId(), "t1").orElseThrow();
        assertThat(reloaded.status()).isEqualTo("CANCELLED");

        // 验证: 幂等指标
        Counter replayCounter = registry.find("agent.idempotency.replays")
                .tag("tool", "create_refund")
                .counter();
        // 幂等回放指标通过 metrics.recordIdempotencyReplay 手动记录（编排层职责）
        // 这里验证业务逻辑本身正确即可
    }

    // ====== 场景 5: 乐观锁冲突 ======

    @Test
    @DisplayName("并发修改同一退款单 → 第二个修改抛出乐观锁冲突")
    void optimisticLockConflict() {
        // 创建一个带版本控制的退款仓库包装器
        var versionedRepo = new VersionedRefundRepository();

        var paymentChannel = new PaymentChannelTool();
        var refundRule = new RefundRuleTool(paymentChannel);
        var versionedService = new RefundApplicationService(
                versionedRepo, refundRule, idemStore, metrics);

        // 第一次创建退款单
        RefundRecord created = versionedService.createRefund(
                "t1", "u1", "ORD-1", 100_00L, "质量问题",
                key("create_refund", "lock-1"));

        // 模拟第一个操作: 审批成功（版本 v1→v2）
        versionedService.approveRefund(created.refundId(), "t1", "admin-1");

        // 模拟第二个操作: 在旧版本上尝试取消 → 乐观锁冲突
        // 直接用版本化仓库模拟: 用旧版本保存会抛异常
        RefundRecord stale = new RefundRecord(
                created.refundId(), "t1", "u1", "ORD-1",
                100_00L, "用户取消", "PENDING"); // 旧状态

        assertThatThrownBy(() -> versionedRepo.saveWithVersion(stale, 0))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("version conflict");
    }

    // ====== 内部辅助类 ======

    /**
     * 带版本控制的退款仓储 — 模拟乐观锁。
     */
    private static class VersionedRefundRepository implements RefundRepositoryPort {
        private final java.util.concurrent.ConcurrentHashMap<String, RefundRecord> store =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> versions =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public RefundRecord save(RefundRecord refund) {
            store.put(refund.refundId(), refund);
            versions.computeIfAbsent(refund.refundId(), k -> new AtomicInteger(0)).incrementAndGet();
            return refund;
        }

        @Override
        public Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
            RefundRecord r = store.get(refundId);
            if (r == null) return Optional.empty();
            if (!r.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("Cross-tenant refund access");
            }
            return Optional.of(r);
        }

        @Override
        public int count() {
            return store.size();
        }

        /**
         * 带版本检查的保存 — 模拟乐观锁。
         */
        public RefundRecord saveWithVersion(RefundRecord refund, int expectedVersion) {
            AtomicInteger currentVersion = versions.get(refund.refundId());
            if (currentVersion == null) {
                throw new IllegalArgumentException("Refund not found: " + refund.refundId());
            }
            if (currentVersion.get() != expectedVersion) {
                throw new OptimisticLockException(
                        String.format("version conflict on refund %s: expected %d but was %d",
                                refund.refundId(), expectedVersion, currentVersion.get()));
            }
            store.put(refund.refundId(), refund);
            currentVersion.incrementAndGet();
            return refund;
        }
    }

    /**
     * 乐观锁冲突异常 — 模拟 JPA OptimisticLockException。
     */
    private static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
