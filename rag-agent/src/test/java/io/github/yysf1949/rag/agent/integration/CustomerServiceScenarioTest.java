package io.github.yysf1949.rag.agent.integration;

import io.github.yysf1949.rag.agent.builtin.*;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.*;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端场景测试 — 验证多工具联动，不启动 Spring context。
 *
 * <p>所有依赖使用 InMemory 实现，手动 wire。</p>
 */
class CustomerServiceScenarioTest {

    // ── Repositories ──
    private InMemoryOrderRepository orderRepo;
    private InMemoryRefundRepository refundRepo;
    private InMemoryComplaintRepository complaintRepo;
    private InMemoryCouponRepository couponRepo;
    private InMemoryTicketRepository ticketRepo;
    private InMemoryNotificationRepository notificationRepo;
    private InMemorySatisfactionSurveyRepository surveyRepo;
    private InMemoryAfterServiceAuditRepository auditRepo;
    private InMemoryMemberProfileRepository memberRepo;
    private InMemoryIdempotencyStore idemStore;

    // ── Tools ──
    private OrderTool orderTool;
    private LogisticsTool logisticsTool;
    private PaymentChannelTool paymentChannelTool;
    private RefundRuleTool refundRuleTool;
    private RefundCalculatorTool refundCalculatorTool;
    private RefundTool refundTool;
    private ComplaintTool complaintTool;
    private CouponTool couponTool;
    private MemberBenefitsTool memberBenefitsTool;
    private NotificationTool notificationTool;
    private SatisfactionSurveyTool surveyTool;
    private AfterServiceTool afterServiceTool;

    @BeforeEach
    void setUp() {
        // repos
        orderRepo = new InMemoryOrderRepository();
        refundRepo = new InMemoryRefundRepository();
        complaintRepo = new InMemoryComplaintRepository();
        couponRepo = new InMemoryCouponRepository();
        ticketRepo = new InMemoryTicketRepository();
        notificationRepo = new InMemoryNotificationRepository();
        surveyRepo = new InMemorySatisfactionSurveyRepository();
        auditRepo = new InMemoryAfterServiceAuditRepository();
        memberRepo = new InMemoryMemberProfileRepository();
        idemStore = new InMemoryIdempotencyStore();

        // tools
        orderTool = new OrderTool(orderRepo);
        logisticsTool = new LogisticsTool();
        paymentChannelTool = new PaymentChannelTool();
        refundRuleTool = new RefundRuleTool(paymentChannelTool);
        refundCalculatorTool = new RefundCalculatorTool(refundRuleTool, orderRepo);
        refundTool = new RefundTool(refundRepo, refundRuleTool);
        complaintTool = new ComplaintTool(complaintRepo, idemStore);
        couponTool = new CouponTool(couponRepo);
        memberBenefitsTool = new MemberBenefitsTool(memberRepo);
        notificationTool = new NotificationTool(notificationRepo);
        surveyTool = new SatisfactionSurveyTool(surveyRepo);
        afterServiceTool = new AfterServiceTool(auditRepo, notificationRepo);
    }

    // ────────────────────────────────────────────────────────────
    // 场景A: 完整退款流程
    // ────────────────────────────────────────────────────────────
    @Test
    void scenarioA_fullRefundFlow() {
        // 1. 创建订单（预置 PAID 状态）
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-A1", "tenant-1", "user-1", 150_00L, "PAID"));

        // 2. 查询订单
        var getOrderResp = orderTool.getOrder(
                new OrderTool.GetOrderRequest("tenant-1", "user-1", "ORD-A1"));
        assertThat(getOrderResp.orderId()).isEqualTo("ORD-A1");
        assertThat(getOrderResp.status()).isEqualTo("PAID");
        assertThat(getOrderResp.amountCents()).isEqualTo(150_00L);

        // 3. 检查退款资格（通过 RefundCalculatorTool）
        var calcResp = refundCalculatorTool.calculate(
                new RefundCalculatorTool.RefundCalcRequest("tenant-1", "user-1", "ORD-A1"));
        assertThat(calcResp.requiresManual()).isFalse();
        assertThat(calcResp.maxRefundableCents()).isEqualTo(150_00L);

        // 4. 计算退款金额（已在上一步完成）
        assertThat(calcResp.maxRefundableCents()).isGreaterThan(0);

        // 5. 创建退款
        var createRefundResp = refundTool.createRefund(
                new RefundTool.CreateRefundRequest("tenant-1", "user-1", "ORD-A1", 150_00L, "用户申请退款"));
        assertThat(createRefundResp.refundId()).startsWith("REF-");
        assertThat(createRefundResp.status()).isEqualTo("PENDING");

        // 6. 执行售后善后（退款确认）
        var afterServiceResp = afterServiceTool.execute(
                new AfterServiceTool.AfterServiceRequest(
                        "tenant-1", "user-1", "ORD-A1", "REFUND_CONFIRMED", 150_00L, null));
        assertThat(afterServiceResp.success()).isTrue();
        assertThat(afterServiceResp.actionType()).isEqualTo("REFUND_CONFIRMED");

        // 验证审计记录
        var audits = auditRepo.findByOrder("ORD-A1");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actionType()).isEqualTo("REFUND_CONFIRMED");

        // 验证通知已发送
        assertThat(notificationRepo.existsByUserAndTemplateWithinWindow(
                "user-1", "REFUND_CREATED", java.time.Duration.ofMinutes(5))).isTrue();

        // 7. 满意度调查
        var surveyResp = surveyTool.submitSurvey(
                new SatisfactionSurveyTool.SurveyRequest(
                        "tenant-1", "user-1", "conv-a1", 4, "退款处理很快", true));
        assertThat(surveyResp.surveyId()).startsWith("SRV-");
        assertThat(surveyResp.rating()).isEqualTo(4);
        assertThat(surveyResp.resolved()).isTrue();
    }

    // ────────────────────────────────────────────────────────────
    // 场景B: 物流投诉流程
    // ────────────────────────────────────────────────────────────
    @Test
    void scenarioB_logisticsComplaintFlow() {
        // 1. 创建订单（已发货状态）
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-B1", "tenant-1", "user-2", 80_00L, "DELIVERED"));

        // 2. 查询物流（已签收但用户未收到 → 使用 DELAYED 标记模拟）
        var logisticsResp = logisticsTool.queryLogistics(
                new LogisticsTool.QueryRequest("tenant-1", "user-2", "ORD-B1-DELAYED"));
        assertThat(logisticsResp.orderId()).isEqualTo("ORD-B1-DELAYED");
        assertThat(logisticsResp.currentLocation()).isEqualTo("中转仓");
        assertThat(logisticsResp.events()).hasSize(2);

        // 3. 创建投诉工单
        var complaintResp = complaintTool.createComplaint(
                new ComplaintTool.ComplaintRequest(
                        "tenant-1", "user-2", "ORD-B1", "LOGISTICS",
                        "显示已签收但实际未收到包裹", "P2"),
                io.github.yysf1949.rag.agent.governance.IdempotencyKey.of(
                        "tenant-1", "user-2", "s1", "create_complaint", "token-b1"));
        assertThat(complaintResp.complaintId()).startsWith("CMP-");
        assertThat(complaintResp.status()).isEqualTo("CREATED");
        assertThat(complaintResp.priority()).isEqualTo("P2");

        // 4. 执行售后善后（投诉升级）
        var afterServiceResp = afterServiceTool.execute(
                new AfterServiceTool.AfterServiceRequest(
                        "tenant-1", "user-2", "ORD-B1", "COMPLAINT_ESCALATED",
                        0, "显示已签收但实际未收到"));
        assertThat(afterServiceResp.success()).isTrue();
        assertThat(afterServiceResp.actionType()).isEqualTo("COMPLAINT_ESCALATED");
        assertThat(afterServiceResp.steps().get(0)).contains("投诉已升级");

        // 验证审计链路
        var audits = auditRepo.findByOrder("ORD-B1");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).success()).isTrue();

        // 验证通知
        assertThat(notificationRepo.existsByUserAndTemplateWithinWindow(
                "user-2", "HUMAN_HANDOFF", java.time.Duration.ofMinutes(5))).isTrue();
    }

    // ────────────────────────────────────────────────────────────
    // 场景C: 优惠券补偿流程
    // ────────────────────────────────────────────────────────────
    @Test
    void scenarioC_couponCompensationFlow() {
        // 1. 创建投诉
        var complaintResp = complaintTool.createComplaint(
                new ComplaintTool.ComplaintRequest(
                        "tenant-1", "user-3", "ORD-C1", "QUALITY",
                        "商品有质量问题", "P2"),
                io.github.yysf1949.rag.agent.governance.IdempotencyKey.of(
                        "tenant-1", "user-3", "s1", "create_complaint", "token-c1"));
        assertThat(complaintResp.complaintId()).startsWith("CMP-");

        // 2. 查询会员权益（预置 GOLD 会员）
        memberRepo.save(new MemberProfileRepositoryPort.MemberProfile(
                "user-3", "tenant-1", "GOLD", 5000L, List.of("优先客服", "专属折扣")));
        var memberResp = memberBenefitsTool.query(
                new MemberBenefitsTool.GetMemberBenefitsRequest("tenant-1", "user-3"));
        assertThat(memberResp.tier()).isEqualTo("GOLD");
        assertThat(memberResp.couponDiscountCents()).isEqualTo(MemberBenefitsTool.GOLD_DISCOUNT_CENTS);

        // 3. 发放优惠券（根据会员等级决定金额）
        long couponAmount = memberResp.couponDiscountCents() > 0
                ? memberResp.couponDiscountCents() : 10_00L;
        var couponResp = couponTool.issueCoupon(
                new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-3", "ORD-C1", couponAmount, "COMPLAINT_COMPENSATION"));
        assertThat(couponResp.couponId()).startsWith("CPN-");
        assertThat(couponResp.amountCents()).isEqualTo(MemberBenefitsTool.GOLD_DISCOUNT_CENTS);
        assertThat(couponResp.status()).isEqualTo("ACTIVE");

        // 4. 满意度调查
        var surveyResp = surveyTool.submitSurvey(
                new SatisfactionSurveyTool.SurveyRequest(
                        "tenant-1", "user-3", "conv-c1", 3, "补偿还行，但希望改进质量", true));
        assertThat(surveyResp.surveyId()).startsWith("SRV-");
        assertThat(surveyResp.rating()).isEqualTo(3);
        assertThat(surveyResp.resolved()).isTrue();

        // 验证优惠券已发放
        var coupons = couponTool.listActiveCoupons(
                new CouponTool.ListCouponsRequest("tenant-1", "user-3"));
        assertThat(coupons.coupons()).hasSize(1);
        assertThat(coupons.coupons().get(0).amountCents()).isEqualTo(MemberBenefitsTool.GOLD_DISCOUNT_CENTS);
    }
}
