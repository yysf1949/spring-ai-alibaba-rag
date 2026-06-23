package io.github.yysf1949.rag.agent.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;

/**
 * Store schema 初始化配置 — 当 {@code spring.rag.store.profile=h2} 激活时
 * 自动执行 {@code CREATE TABLE IF NOT EXISTS} 确保所有表存在。
 *
 * <h2>设计意图</h2>
 * <p>各 H2*Repository 以 {@code @Profile("h2")} 注册，本配置在同一 profile 下
 * 自动建表，避免启动时 "table not found" 异常。</p>
 *
 * <h2>表清单</h2>
 * <ul>
 *   <li>{@code agent_complaint} — 投诉工单</li>
 *   <li>{@code agent_inventory} — 商品库存</li>
 *   <li>{@code agent_member_profile} — 会员档案</li>
 *   <li>{@code agent_notification} — 站内通知</li>
 *   <li>{@code agent_price_protection} — 价保申请</li>
 *   <li>{@code agent_user_profile} — 用户档案</li>
 *   <li>{@code agent_user_address} — 用户地址</li>
 *   <li>{@code agent_satisfaction_survey} — 满意度调查</li>
 *   <li>{@code agent_after_service_audit} — 售后善后审计</li>
 * </ul>
 *
 * <h2>为什么不用 Flyway/Liquibase</h2>
 * <p>rag-agent 设计为零外部依赖可运行。H2 + MERGE INTO 已满足 demo/教学需求。
 * 生产迁移由运维侧统一管理。</p>
 */
@Configuration
@Profile("h2")
public class StoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StoreAutoConfiguration.class);

    private final JdbcTemplate jdbc;

    public StoreAutoConfiguration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        ensureAllSchema(jdbc);
    }

    /**
     * 对外暴露的静态建表入口 — 测试可直接调用。
     *
     * @param ds 数据源（H2 内存或文件）
     */
    public static void ensureAllSchema(DataSource ds) {
        ensureAllSchema(new JdbcTemplate(ds));
    }

    /**
     * 对外暴露的建表入口 — 测试可直接调用。
     *
     * @param jdbc JdbcTemplate（已绑定 DataSource）
     */
    public static void ensureAllSchema(JdbcTemplate jdbc) {
        log.info("StoreAutoConfiguration.ensureAllSchema — creating tables if not exist...");

        // 1. agent_complaint — 投诉工单
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_complaint (
                    complaint_id  VARCHAR(64)  PRIMARY KEY,
                    tenant_id     VARCHAR(64)  NOT NULL,
                    user_id       VARCHAR(64)  NOT NULL,
                    order_id      VARCHAR(64),
                    category      VARCHAR(64),
                    description   VARCHAR(1024),
                    priority      VARCHAR(16),
                    status        VARCHAR(32)  NOT NULL,
                    created_at    BIGINT       NOT NULL
                )
                """);

        // 2. agent_inventory — 商品库存
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_inventory (
                    product_id         VARCHAR(64)  PRIMARY KEY,
                    tenant_id          VARCHAR(64)  NOT NULL,
                    product_name       VARCHAR(256),
                    available_quantity INT          NOT NULL DEFAULT 0,
                    on_sale            BOOLEAN      NOT NULL DEFAULT FALSE,
                    price_cents        BIGINT       NOT NULL DEFAULT 0,
                    category           VARCHAR(128)
                )
                """);

        // 3. agent_member_profile — 会员档案
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_member_profile (
                    user_id        VARCHAR(64)  NOT NULL,
                    tenant_id      VARCHAR(64)  NOT NULL,
                    tier           VARCHAR(32)  NOT NULL DEFAULT 'NORMAL',
                    points_balance BIGINT       NOT NULL DEFAULT 0,
                    perks          VARCHAR(1024),
                    PRIMARY KEY (user_id, tenant_id)
                )
                """);

        // 4. agent_notification — 站内通知
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_notification (
                    notification_id VARCHAR(64)  PRIMARY KEY,
                    tenant_id       VARCHAR(64)  NOT NULL,
                    user_id         VARCHAR(64)  NOT NULL,
                    template        VARCHAR(128),
                    content         VARCHAR(2048),
                    sent_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        // 5. agent_price_protection — 价保申请
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_price_protection (
                    claim_id             VARCHAR(64)  PRIMARY KEY,
                    tenant_id            VARCHAR(64)  NOT NULL,
                    user_id              VARCHAR(64)  NOT NULL,
                    order_id             VARCHAR(64)  NOT NULL,
                    product_id           VARCHAR(64)  NOT NULL,
                    refund_amount_cents  BIGINT       NOT NULL DEFAULT 0,
                    original_price_cents BIGINT       NOT NULL DEFAULT 0,
                    current_price_cents  BIGINT       NOT NULL DEFAULT 0,
                    status               VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
                    reason               VARCHAR(512),
                    idempotency_key      VARCHAR(128)
                )
                """);

        // 6. agent_user_profile — 用户档案
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_user_profile (
                    user_id      VARCHAR(64)  NOT NULL,
                    tenant_id    VARCHAR(64)  NOT NULL,
                    nickname     VARCHAR(128),
                    phone        VARCHAR(32),
                    email        VARCHAR(128),
                    vip_level    VARCHAR(32)  NOT NULL DEFAULT 'NORMAL',
                    PRIMARY KEY (user_id, tenant_id)
                )
                """);

        // 7. agent_user_address — 用户地址
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_user_address (
                    address_id   VARCHAR(64)  NOT NULL,
                    user_id      VARCHAR(64)  NOT NULL,
                    tenant_id    VARCHAR(64)  NOT NULL,
                    receiver     VARCHAR(128),
                    phone        VARCHAR(32),
                    province     VARCHAR(64),
                    city         VARCHAR(64),
                    district     VARCHAR(64),
                    detail       VARCHAR(512),
                    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
                    PRIMARY KEY (address_id)
                )
                """);

        // 8. agent_satisfaction_survey — 满意度调查
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_satisfaction_survey (
                    survey_id       VARCHAR(64)  PRIMARY KEY,
                    tenant_id       VARCHAR(64)  NOT NULL,
                    user_id         VARCHAR(64)  NOT NULL,
                    conversation_id VARCHAR(128) NOT NULL,
                    rating          INT          NOT NULL,
                    feedback        VARCHAR(2048),
                    resolved        BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at      BIGINT       NOT NULL
                )
                """);

        // 9. agent_after_service_audit — 售后善后审计
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS agent_after_service_audit (
                    audit_id    VARCHAR(64)  PRIMARY KEY,
                    order_id    VARCHAR(64)  NOT NULL,
                    action_type VARCHAR(64)  NOT NULL,
                    steps       CLOB,
                    success     BOOLEAN      NOT NULL DEFAULT TRUE,
                    created_at  BIGINT       NOT NULL
                )
                """);

        // 10. agent_feedback — 用户反馈 (Phase 40 T1, R10 Active Learning)
        jdbc.update("""
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
                )
                """);
        jdbc.update("CREATE INDEX IF NOT EXISTS idx_agent_feedback_tenant_created "
                + "ON agent_feedback (tenant_id, created_at)");
        jdbc.update("CREATE INDEX IF NOT EXISTS idx_agent_feedback_tenant_conv "
                + "ON agent_feedback (tenant_id, conversation_id)");

                // 11. agent_invoice — 支付发票 (Phase 40 T4, R11 商业化收口)
        jdbc.update("""
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
                )
                """);
        jdbc.update("CREATE INDEX IF NOT EXISTS idx_agent_invoice_tenant_created "
                + "ON agent_invoice (tenant_id, created_at DESC)");
        jdbc.update("CREATE INDEX IF NOT EXISTS idx_agent_invoice_external_ref "
                + "ON agent_invoice (external_ref)");

        log.info("StoreAutoConfiguration.ensureAllSchema — all 11 tables ready.");
    }
}
