-- Phase 11 Task 4: H2 DDL for @Profile("h2") persistent backend
-- Tables match H2OrderRepository / H2RefundRepository / H2CouponRepository / H2TicketRepository

CREATE TABLE IF NOT EXISTS agent_order (
    order_id   VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(64)   NOT NULL,
    user_id    VARCHAR(128)  NOT NULL,
    amount_cents BIGINT      NOT NULL DEFAULT 0,
    status     VARCHAR(32)   NOT NULL DEFAULT 'CREATED'
);

CREATE TABLE IF NOT EXISTS agent_refund (
    refund_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(128) NOT NULL,
    order_id     VARCHAR(64)  NOT NULL,
    amount_cents BIGINT       NOT NULL DEFAULT 0,
    reason       VARCHAR(512),
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
);

CREATE TABLE IF NOT EXISTS agent_coupon (
    coupon_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(128) NOT NULL,
    order_id     VARCHAR(64),
    amount_cents BIGINT       NOT NULL DEFAULT 0,
    reason_tag   VARCHAR(64),
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS agent_ticket (
    ticket_id   VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64)   NOT NULL,
    user_id     VARCHAR(128)  NOT NULL,
    summary     VARCHAR(1024),
    status      VARCHAR(32)   NOT NULL DEFAULT 'OPEN',
    created_at  BIGINT        NOT NULL
);

-- Phase 40 T1: agent_feedback table (R10 Active Learning)
CREATE TABLE IF NOT EXISTS agent_feedback (
    feedback_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    user_id         VARCHAR(128)  NOT NULL,
    conversation_id VARCHAR(128)  NOT NULL,
    message_id      VARCHAR(128),
    thumb           VARCHAR(8),
    rating          INT,
    comment         VARCHAR(2048),
    source_channel  VARCHAR(32)   NOT NULL DEFAULT 'api',
    kb_version      VARCHAR(64),
    created_at      BIGINT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_feedback_tenant_created
    ON agent_feedback (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_feedback_tenant_conv
    ON agent_feedback (tenant_id, conversation_id);

-- Phase 40 T4: agent_invoice table (R11 商业化收口 — 支付发票)
CREATE TABLE IF NOT EXISTS agent_invoice (
    invoice_id      VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    amount_cents    BIGINT        NOT NULL,
    currency        VARCHAR(8)    NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    paid_at         BIGINT,
    payment_method  VARCHAR(16)   NOT NULL,
    external_ref    VARCHAR(128),
    description     VARCHAR(512),
    created_at      BIGINT        NOT NULL,
    refunded_at     BIGINT,
    refund_reason   VARCHAR(512)
);
CREATE INDEX IF NOT EXISTS idx_agent_invoice_tenant_created
    ON agent_invoice (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_invoice_external_ref
    ON agent_invoice (external_ref);
