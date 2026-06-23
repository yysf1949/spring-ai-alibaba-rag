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

-- Phase 40 T3: agent_tenant_quota — Tenant 配额元数据 (R11)
CREATE TABLE IF NOT EXISTS agent_tenant_quota (
    tenant_id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    tier                 VARCHAR(16)   NOT NULL DEFAULT 'FREE',
    monthly_call_limit   BIGINT        NOT NULL DEFAULT 1000,
    monthly_token_limit  BIGINT        NOT NULL DEFAULT 100000,
    effective_from       BIGINT        NOT NULL,
    effective_to         BIGINT,
    downgraded_at        BIGINT,
    original_tier        VARCHAR(16)       -- 降级前原 tier, 降级清除时恢复
);

-- Phase 40 T3: agent_usage_counter — 月度用量计数 (calls + tokens)
CREATE TABLE IF NOT EXISTS agent_usage_counter (
    tenant_id      VARCHAR(64)  NOT NULL,
    month_key      VARCHAR(7)   NOT NULL,    -- YYYY-MM (UTC)
    resource       VARCHAR(16)  NOT NULL,    -- "calls" / "tokens"
    counter_value  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, month_key, resource)
);
CREATE INDEX IF NOT EXISTS idx_agent_usage_counter_month
    ON agent_usage_counter (month_key);