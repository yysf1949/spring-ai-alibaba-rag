package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.*;
import io.github.yysf1949.rag.agent.builtin.store.*;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for 5 new business tools:
 * UserIdentityTool, RefundStatusTool, InventoryTool, ComplaintTool, OrderTool (listOrders).
 */
class AllNewToolsTest {

    // ──────────────────────────────────────────────────────────────────────────────
    // UserIdentityTool
    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    class UserIdentityToolTests {

        private InMemoryUserIdentityRepository repo;
        private UserIdentityTool tool;

        @BeforeEach
        void setUp() {
            repo = new InMemoryUserIdentityRepository();
            tool = new UserIdentityTool(repo);
        }

        @Test
        void queryUserInfoReturnsProfileAndAddressesWhenFound() {
            // given
            var profile = new UserIdentityPort.UserProfile(
                    "user-1", "tenant-1", "小明", "张小明",
                    "13800000001", "xiaoming@example.com", "GOLD", 1200L);
            repo.saveProfile(profile);
            repo.saveAddresses("tenant-1", "user-1", List.of(
                    new UserIdentityPort.Address(
                            "addr-1", "张小明", "13800000001",
                            "北京市", "北京市", "朝阳区", "望京SOHO T1", true),
                    new UserIdentityPort.Address(
                            "addr-2", "张小明", "13800000001",
                            "上海市", "上海市", "浦东新区", "陆家嘴环路1000号", false)
            ));

            // when
            var resp = tool.queryUserInfo(new UserIdentityTool.UserInfoRequest("tenant-1", "user-1"));

            // then
            assertThat(resp.userId()).isEqualTo("user-1");
            assertThat(resp.memberLevel()).isEqualTo("GOLD");
            assertThat(resp.nickname()).isEqualTo("小明");
            assertThat(resp.points()).isEqualTo(1200L);
            assertThat(resp.addresses()).hasSize(2);
            assertThat(resp.addresses().get(0).addressId()).isEqualTo("addr-1");
            assertThat(resp.addresses().get(1).addressId()).isEqualTo("addr-2");
            assertThat(resp.error()).isNull();
        }

        @Test
        void queryUserInfoReturnsErrorWhenUserNotFound() {
            // when
            var resp = tool.queryUserInfo(new UserIdentityTool.UserInfoRequest("tenant-1", "nonexistent"));

            // then
            assertThat(resp.userId()).isEqualTo("nonexistent");
            assertThat(resp.memberLevel()).isEqualTo("UNKNOWN");
            assertThat(resp.nickname()).isEqualTo("UNKNOWN");
            assertThat(resp.points()).isEqualTo(0);
            assertThat(resp.addresses()).isEmpty();
            assertThat(resp.error()).isEqualTo("用户不存在");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // RefundStatusTool
    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    class RefundStatusToolTests {

        private InMemoryRefundRepository repo;
        private RefundStatusTool tool;

        @BeforeEach
        void setUp() {
            repo = new InMemoryRefundRepository();
            tool = new RefundStatusTool(repo);
        }

        @Test
        void queryRefundReturnsDetailsWhenFound() {
            // given
            repo.save(new RefundRepositoryPort.RefundRecord(
                    "REF-1", "tenant-1", "user-1", "ORD-1",
                    50_00L, "商品质量问题", "PENDING"));

            // when
            var resp = tool.queryRefund(new RefundStatusTool.QueryRefundRequest("tenant-1", "REF-1"));

            // then
            assertThat(resp.refundId()).isEqualTo("REF-1");
            assertThat(resp.status()).isEqualTo("PENDING");
            assertThat(resp.amountCents()).isEqualTo(50_00L);
            assertThat(resp.reason()).isEqualTo("商品质量问题");
            assertThat(resp.error()).isNull();
        }

        @Test
        void queryRefundReturnsNotFoundWhenRefundDoesNotExist() {
            // when
            var resp = tool.queryRefund(new RefundStatusTool.QueryRefundRequest("tenant-1", "REF-999"));

            // then
            assertThat(resp.refundId()).isEqualTo("REF-999");
            assertThat(resp.status()).isEqualTo("NOT_FOUND");
            assertThat(resp.amountCents()).isEqualTo(0);
            assertThat(resp.reason()).isNull();
            assertThat(resp.error()).isEqualTo("退款记录不存在");
        }

        @Test
        void cancelRefundCancelsPendingRefundSuccessfully() {
            // given
            repo.save(new RefundRepositoryPort.RefundRecord(
                    "REF-1", "tenant-1", "user-1", "ORD-1",
                    50_00L, "不想要了", "PENDING"));

            // when
            var resp = tool.cancelRefund(new RefundStatusTool.CancelRefundRequest(
                    "tenant-1", "user-1", "REF-1"));

            // then
            assertThat(resp.refundId()).isEqualTo("REF-1");
            assertThat(resp.status()).isEqualTo("CANCELLED");
            assertThat(resp.message()).isEqualTo("退款申请已取消");

            // verify the repo was updated
            var stored = repo.findByIdAndTenant("REF-1", "tenant-1");
            assertThat(stored).isPresent();
            assertThat(stored.get().status()).isEqualTo("CANCELLED");
        }

        @Test
        void cancelRefundRejectsWhenRefundIsApproved() {
            // given
            repo.save(new RefundRepositoryPort.RefundRecord(
                    "REF-2", "tenant-1", "user-1", "ORD-2",
                    80_00L, "商品损坏", "APPROVED"));

            // when
            var resp = tool.cancelRefund(new RefundStatusTool.CancelRefundRequest(
                    "tenant-1", "user-1", "REF-2"));

            // then
            assertThat(resp.refundId()).isEqualTo("REF-2");
            assertThat(resp.status()).isEqualTo("APPROVED");
            assertThat(resp.message()).contains("APPROVED").contains("无法取消");
        }

        @Test
        void cancelRefundReturnsNotFoundWhenRefundDoesNotExist() {
            // when
            var resp = tool.cancelRefund(new RefundStatusTool.CancelRefundRequest(
                    "tenant-1", "user-1", "REF-999"));

            // then
            assertThat(resp.refundId()).isEqualTo("REF-999");
            assertThat(resp.status()).isEqualTo("NOT_FOUND");
            assertThat(resp.message()).isEqualTo("退款记录不存在");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // InventoryTool
    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    class InventoryToolTests {

        private InMemoryInventoryRepository repo;
        private InventoryTool tool;

        @BeforeEach
        void setUp() {
            repo = new InMemoryInventoryRepository();
            tool = new InventoryTool(repo);
        }

        @Test
        void checkStockReturnsStockInfoWhenProductFound() {
            // given
            repo.save(new InventoryPort.ProductStock(
                    "PROD-1", "tenant-1", "蓝牙耳机", 50, true, 199_00L, "数码"));

            // when
            var resp = tool.checkStock(new InventoryTool.StockRequest("tenant-1", "PROD-1"));

            // then
            assertThat(resp.productId()).isEqualTo("PROD-1");
            assertThat(resp.productName()).isEqualTo("蓝牙耳机");
            assertThat(resp.availableQuantity()).isEqualTo(50);
            assertThat(resp.onSale()).isTrue();
            assertThat(resp.priceCents()).isEqualTo(199_00L);
            assertThat(resp.category()).isEqualTo("数码");
            assertThat(resp.error()).isNull();
        }

        @Test
        void checkStockReturnsErrorWhenProductNotFound() {
            // when
            var resp = tool.checkStock(new InventoryTool.StockRequest("tenant-1", "PROD-999"));

            // then
            assertThat(resp.productId()).isEqualTo("PROD-999");
            assertThat(resp.productName()).isEqualTo("UNKNOWN");
            assertThat(resp.availableQuantity()).isEqualTo(0);
            assertThat(resp.onSale()).isFalse();
            assertThat(resp.priceCents()).isEqualTo(0);
            assertThat(resp.category()).isNull();
            assertThat(resp.error()).isEqualTo("商品不存在");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // ComplaintTool
    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    class ComplaintToolTests {

        private InMemoryComplaintRepository repo;
        private InMemoryIdempotencyStore idempotencyStore;
        private ComplaintTool tool;

        @BeforeEach
        void setUp() {
            repo = new InMemoryComplaintRepository();
            idempotencyStore = new InMemoryIdempotencyStore();
            tool = new ComplaintTool(repo, idempotencyStore);
        }

        @Test
        void createComplaintWithP2Priority() {
            // given
            var req = new ComplaintTool.ComplaintRequest(
                    "tenant-1", "user-1", "ORD-1",
                    "SERVICE", "客服态度恶劣", "P2");
            var key = IdempotencyKey.of("tenant-1", "user-1", "session-1",
                    "create_complaint", "token-abc");

            // when
            var resp = tool.createComplaint(req, key);

            // then
            assertThat(resp.complaintId()).startsWith("CMP-");
            assertThat(resp.status()).isEqualTo("CREATED");
            assertThat(resp.priority()).isEqualTo("P2");
            assertThat(resp.message()).contains("投诉已受理");
        }

        @Test
        void createComplaintEscalatesP0Complaints() {
            // given
            var req = new ComplaintTool.ComplaintRequest(
                    "tenant-1", "user-1", "ORD-1",
                    "QUALITY", "商品爆炸起火", "P0");
            var key = IdempotencyKey.of("tenant-1", "user-1", "session-1",
                    "create_complaint", "token-p0");

            // when
            var resp = tool.createComplaint(req, key);

            // then
            assertThat(resp.complaintId()).isNull();
            assertThat(resp.status()).isEqualTo("ESCALATED");
            assertThat(resp.priority()).isEqualTo("P0");
            assertThat(resp.message()).contains("紧急投诉").contains("转接专属客服经理");
        }

        @Test
        void createComplaintIsIdempotent() {
            // given — same key used twice
            var req = new ComplaintTool.ComplaintRequest(
                    "tenant-1", "user-1", "ORD-1",
                    "LOGISTICS", "快递丢失", "P2");
            var key = IdempotencyKey.of("tenant-1", "user-1", "session-1",
                    "create_complaint", "token-idem");

            // when — first call
            var resp1 = tool.createComplaint(req, key);
            // when — second call with identical key
            var resp2 = tool.createComplaint(req, key);

            // then — second call is a replay returning the same complaint ID
            assertThat(resp2.complaintId()).isEqualTo(resp1.complaintId());
            assertThat(resp2.status()).isEqualTo("CREATED");
            assertThat(resp2.message()).contains("幂等回放");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // OrderTool — listOrders
    // ──────────────────────────────────────────────────────────────────────────────
    @Nested
    class OrderToolListOrdersTests {

        private InMemoryOrderRepository repo;
        private OrderTool tool;

        @BeforeEach
        void setUp() {
            repo = new InMemoryOrderRepository();
            tool = new OrderTool(repo);
        }

        @Test
        void listOrdersReturnsUserOrders() {
            // given — two orders for user-1, one for user-2
            repo.save(new OrderRepositoryPort.OrderRecord(
                    "ORD-1", "tenant-1", "user-1", 100_00L, "CREATED"));
            repo.save(new OrderRepositoryPort.OrderRecord(
                    "ORD-2", "tenant-1", "user-1", 200_00L, "PAID"));
            repo.save(new OrderRepositoryPort.OrderRecord(
                    "ORD-3", "tenant-1", "user-2", 50_00L, "DELIVERED"));

            // when
            var resp = tool.listOrders(new OrderTool.ListOrdersRequest("tenant-1", "user-1"));

            // then
            assertThat(resp.total()).isEqualTo(2);
            assertThat(resp.orders()).hasSize(2);
            assertThat(resp.orders()).extracting(OrderTool.OrderBrief::orderId)
                    .containsExactlyInAnyOrder("ORD-1", "ORD-2");
            // verify brief fields
            var ord1 = resp.orders().stream().filter(b -> b.orderId().equals("ORD-1")).findFirst();
            assertThat(ord1).isPresent();
            assertThat(ord1.get().status()).isEqualTo("CREATED");
            assertThat(ord1.get().amountCents()).isEqualTo(100_00L);
        }

        @Test
        void listOrdersReturnsEmptyListWhenNoOrders() {
            // when
            var resp = tool.listOrders(new OrderTool.ListOrdersRequest("tenant-1", "ghost-user"));

            // then
            assertThat(resp.total()).isEqualTo(0);
            assertThat(resp.orders()).isEmpty();
        }
    }
}
