package io.github.yysf1949.rag.agent.payment.web;

import io.github.yysf1949.rag.agent.payment.PaymentPort;
import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Invoice 列表/详情 API — Phase 40 T4.
 *
 * <p>仅 GET (查询). 创建 checkout / 退款由专门的 admin 接口负责
 * (本期范围留给后续 T5 Admin UI).</p>
 */
@RestController
@RequestMapping("/api/agent/invoice")
@Tag(name = "Invoice", description = "Phase 40 T4: 支付发票查询 API (R11).")
public class InvoiceController {

    private final PaymentPort paymentPort;

    public InvoiceController(PaymentPort paymentPort) {
        this.paymentPort = paymentPort;
    }

    @GetMapping
    @Operation(summary = "列出当前 tenant 的 invoice")
    public ResponseEntity<List<Invoice>> listInvoices(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return ResponseEntity.ok(paymentPort.listByTenant(tenantId, Math.max(1, Math.min(limit, 1000))));
    }

    @GetMapping("/{invoiceId}")
    @Operation(summary = "单条 invoice 详情 (跨租户访问返回 404)")
    public ResponseEntity<Invoice> getInvoice(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String invoiceId) {
        return paymentPort.findInvoice(tenantId, invoiceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
