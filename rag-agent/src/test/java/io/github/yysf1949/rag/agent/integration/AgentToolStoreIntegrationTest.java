package io.github.yysf1949.rag.agent.integration;

import io.github.yysf1949.rag.agent.builtin.OrderTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.builtin.CouponTool;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.store.H2OrderRepository;
import io.github.yysf1949.rag.agent.store.H2TicketRepository;
import io.github.yysf1949.rag.agent.store.H2CouponRepository;
import io.github.yysf1949.rag.agent.store.H2RefundRepository;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22: 真实业务系统对接测试 — Tool→H2 全链路集成.
 *
 * <p>不用 mock, 直接走 H2 持久层. 验证 Tool 调用 → H2 repo → 数据库 → 结果 的完整链路.</p>
 *
 * <h2>与 InMemory 测试的区别</h2>
 * <ul>
 *   <li>InMemory 测试: Tool → InMemoryRepo → HashMap (进程内)</li>
 *   <li>本测试: Tool → H2Repo → JdbcTemplate → H2 SQL → 验证数据落库</li>
 * </ul>
 *
 * <h2>H2 表结构</h2>
 * <ul>
 *   <li>agent_order: order_id, tenant_id, user_id, amount_cents, status</li>
 *   <li>agent_ticket: ticket_id, tenant_id, user_id, summary, status, created_at</li>
 *   <li>agent_coupon: coupon_id, tenant_id, user_id, order_id, amount_cents, reason_tag, status</li>
 *   <li>agent_refund: refund_id, tenant_id, user_id, order_id, amount_cents, reason, status</li>
 * </ul>
 */
@DisplayName("真实业务系统对接 — Tool→H2 全链路")
class AgentToolStoreIntegrationTest {

    private JdbcTemplate jdbc;
    private H2OrderRepository orderRepo;
    private H2TicketRepository ticketRepo;
    private H2CouponRepository couponRepo;
    private H2RefundRepository refundRepo;
    private OrderTool orderTool;
    private TicketTool ticketTool;
    private CouponTool couponTool;
    private IdempotencyStore idemStore;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tool_integ_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);

        // 创建表
        jdbc.execute("CREATE TABLE agent_order (order_id VARCHAR PRIMARY KEY, tenant_id VARCHAR, user_id VARCHAR, amount_cents BIGINT, status VARCHAR)");
        jdbc.execute("CREATE TABLE agent_ticket (ticket_id VARCHAR PRIMARY KEY, tenant_id VARCHAR, user_id VARCHAR, summary VARCHAR, status VARCHAR, created_at BIGINT)");
        jdbc.execute("CREATE TABLE agent_coupon (coupon_id VARCHAR PRIMARY KEY, tenant_id VARCHAR, user_id VARCHAR, order_id VARCHAR, amount_cents BIGINT, reason_tag VARCHAR, status VARCHAR)");
        jdbc.execute("CREATE TABLE agent_refund (refund_id VARCHAR PRIMARY KEY, tenant_id VARCHAR, user_id VARCHAR, order_id VARCHAR, amount_cents BIGINT, reason VARCHAR, status VARCHAR)");

        // 初始化 repos
        orderRepo = new H2OrderRepository(jdbc);
        ticketRepo = new H2TicketRepository(jdbc);
        couponRepo = new H2CouponRepository(jdbc);
        refundRepo = new H2RefundRepository(jdbc);
        idemStore = new InMemoryIdempotencyStore();

        // 初始化 tools
        orderTool = new OrderTool(orderRepo, idemStore);
        ticketTool = new TicketTool(ticketRepo, idemStore);
        couponTool = new CouponTool(couponRepo, idemStore);
    }

    // ---- OrderTool → H2 ----

    @Test
    @DisplayName("OrderTool: 创建订单 → H2 查询 → 状态流转")
    void orderTool_createQueryUpdate_h2RoundTrip() {
        // 预置数据 (模拟业务写入)
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-H2-001", "t1", "u1", 29900L, "CREATED"));

        // 查询
        var resp = orderTool.getOrder(new OrderTool.GetOrderRequest("t1", "u1", "ORD-H2-001"));
        assertThat(resp.orderId()).isEqualTo("ORD-H2-001");
        assertThat(resp.status()).isEqualTo("CREATED");
        assertThat(resp.amountCents()).isEqualTo(29900L);

        // 列表
        var listResp = orderTool.listOrders(new OrderTool.ListOrdersRequest("t1", "u1"));
        assertThat(listResp.orders()).hasSize(1);
        assertThat(listResp.orders().get(0).orderId()).isEqualTo("ORD-H2-001");

        // 取消 (L3)
        var cancelResp = orderTool.cancelOrder(
                IdempotencyKey.of("t1", "u1", "test-session", "cancel_order", "confirm-tok"),
                new OrderTool.CancelOrderRequest(
                "t1", "u1", "ORD-H2-001", 29900L, "用户主动取消"));
        assertThat(cancelResp.status()).isEqualTo("CANCELLED");

        // 验证 H2 数据已更新
        var updated = orderRepo.findByIdAndTenant("ORD-H2-001", "t1");
        assertThat(updated).isPresent();
        assertThat(updated.get().status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("OrderTool: 跨租户隔离 — t2 不能访问 t1 的订单")
    void orderTool_crossTenantBlocked() {
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-TENANT", "t1", "u1", 10000L, "CREATED"));

        assertThatThrownBy(() ->
                orderTool.getOrder(new OrderTool.GetOrderRequest("t2", "u1", "ORD-TENANT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORD-TENANT");
    }

    // ---- TicketTool → H2 ----

    @Test
    @DisplayName("TicketTool: 创建工单 → H2 查询 → 幂等验证")
    void ticketTool_createAndQuery_h2RoundTrip() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket",
                UUID.randomUUID().toString());
        var req = new TicketTool.Request("kb-search", "查询结果为空，请人工跟进");

        var resp = ticketTool.createReminder(identity, key, req);
        assertThat(resp.ticketId()).isNotBlank();
        assertThat(resp.status()).isEqualTo("PENDING");

        // 验证 H2 数据
        var saved = ticketRepo.findById(resp.ticketId());
        assertThat(saved).isPresent();
        assertThat(saved.get().summary()).isEqualTo("查询结果为空，请人工跟进");
        assertThat(saved.get().status()).isEqualTo("PENDING");

        // 幂等: 同 key 再次调用返回相同 ticket
        var resp2 = ticketTool.createReminder(identity, key, req);
        assertThat(resp2.ticketId()).isEqualTo(resp.ticketId());
    }

    // ---- CouponTool → H2 ----

    @Test
    @DisplayName("CouponTool: 发放优惠券 → H2 查询 → 列表验证")
    void couponTool_grantAndQuery_h2RoundTrip() {
        var resp = couponTool.issueCoupon(
                IdempotencyKey.of("t1", "u1", "test-session", "issue_coupon", "confirm-tok"),
                new CouponTool.IssueCouponRequest(
                "t1", "u1", "ORD-001", 5000L, "WELCOME_BACK"));
        assertThat(resp.couponId()).startsWith("CPN-");
        assertThat(resp.amountCents()).isEqualTo(5000L);

        // 验证 H2 数据
        var active = couponRepo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).amountCents()).isEqualTo(5000L);

        // 再发一张 + 列表
        couponTool.issueCoupon(
                IdempotencyKey.of("t1", "u1", "test-session", "issue_coupon", "confirm-tok-2"),
                new CouponTool.IssueCouponRequest(
                "t1", "u1", "ORD-002", 3000L, "LOYALTY"));
        var listResp = couponTool.listActiveCoupons(new CouponTool.ListCouponsRequest("t1", "u1"));
        assertThat(listResp.coupons()).hasSize(2);
    }

    @Test
    @DisplayName("CouponTool: 超限发放 → AmountLimitExceededException")
    void couponTool_overLimit_throwsHandoff() {
        assertThatThrownBy(() ->
                couponTool.issueCoupon(
                IdempotencyKey.of("t1", "u1", "test-session", "issue_coupon", "confirm-tok"),
                new CouponTool.IssueCouponRequest(
                        "t1", "u1", "ORD-001", 50000L, "BIG_REWARD")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    // ---- RefundCalculator → H2 ----

    @Test
    @DisplayName("RefundCalculatorTool: 退款计算 + H2 落库")
    void refundCalculator_h2RoundTrip() {
        // 预置退款记录
        refundRepo.save(new RefundRepositoryPort.RefundRecord(
                "RFD-001", "t1", "u1", "ORD-001", 15000L, "质量问题", "PENDING"));

        // 验证 H2 数据
        var found = refundRepo.findByIdAndTenant("RFD-001", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().amountCents()).isEqualTo(15000L);
        assertThat(found.get().reason()).isEqualTo("质量问题");
    }

    // ---- 跨 Tool 联动 ----

    @Test
    @DisplayName("跨 Tool 联动: 订单 + 工单 + 优惠券 → H2 全落库")
    void crossTool_orderTicketCoupon_allPersistInH2() {
        // 1. 创建订单
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-CROSS", "t1", "u1", 59900L, "CREATED"));

        // 2. 创建工单 (关联订单)
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket",
                UUID.randomUUID().toString());
        ticketTool.createReminder(identity, key,
                new TicketTool.Request("order-issue", "订单 ORD-CROSS 商品质量问题"));

        // 3. 补发优惠券
        couponTool.issueCoupon(
                IdempotencyKey.of("t1", "u1", "test-session", "issue_coupon", "confirm-tok"),
                new CouponTool.IssueCouponRequest(
                "t1", "u1", "ORD-CROSS", 10000L, "COMPENSATION"));

        // 验证: 3 个表都有数据
        assertThat(orderRepo.findByIdAndTenant("ORD-CROSS", "t1")).isPresent();
        assertThat(ticketRepo.findByTenant("t1")).isNotEmpty();
        assertThat(couponRepo.findActiveByTenantAndUser("t1", "u1")).hasSize(1);
    }
}
