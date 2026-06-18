-- Phase 11: H2 DDL for InMemory → persistent migration (readied for Task 3~6)
-- Run on dev/prod H2 console; production uses MySQL via Flyway.

CREATE TABLE IF NOT EXISTS orders (
    order_id    VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    amount_cents BIGINT     NOT NULL,
    status      VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS refunds (
    refund_id   VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    order_id    VARCHAR(64) NOT NULL,
    amount_cents BIGINT     NOT NULL,
    reason      VARCHAR(256),
    status      VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS coupons (
    coupon_id   VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    order_id    VARCHAR(64),
    amount_cents BIGINT     NOT NULL,
    reason_tag  VARCHAR(64),
    status      VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    ticket_id   VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    summary     VARCHAR(512) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  BIGINT NOT NULL
);