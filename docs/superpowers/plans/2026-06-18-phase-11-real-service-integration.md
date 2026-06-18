# Phase 11 — 真实业务 Service 集成 (多存储后端持久化)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Phase 10 的 4 个 InMemory Repository（Order/Refund/Coupon/Ticket）**抽象为 Port interface + 3 种持久化实现**，并修复 `TicketTool` 对 `InMemoryTicketRepository` 的直接依赖（反模式）。

**Architecture:**
- 每个 Repository 拆成 **Port interface**（rag-agent → 业务工具只依赖接口）
- **3 种持久化后端**，通过 `@Profile` 切换：
  1. **H2 (embedded)** — dev 环境，0 配置，HSQL/JDBC
  2. **MySQL** — 生产环境，Spring Data JPA
  3. **Redis** — 高性能/分布式，rag-redis `JedisPooled`
- **InMemory** 保留为单元测试默认（不设 `@Profile`）
- 不动 rag-core / rag-pipeline / rag-embedding / rag-app 等旧模块

**Key decisions:**
1. **不抽成 rag-core port** — Repository 是 Agent 业务工具专属，Port interface 放 `rag-agent.builtin.port`
2. **H2 用 Spring JDBC `JdbcTemplate`**（轻量，不拉 JPA），schema 启动时 auto-create
3. **MySQL 用 Spring Data JPA**（`@Entity` + `JpaRepository`），DDL auto-update
4. **Redis 用 `JedisPooled` + `ObjectMapper`**（已建的 `RedisStoreFactory`），JSON 序列化
5. **Profile 切换**: `dev` → InMemory（默认）/ H2（embedded），`prod` → MySQL / Redis
6. **Ticket 无 tenantId** — `findByTenant(tenantId)` 用 Redis 的 `keys agent:ticket:*` 或 SQL `WHERE tenant_id = ?`

**3 种存储对比:**

| 维度 | InMemory | H2 | MySQL | Redis |
|---|---|---|---|---|
| 用途 | 单元测试 | dev 开发 | 生产 | 高性能/分布式 |
| 依赖 | 无 | h2 (runtime) + spring-jdbc | mysql-connector-j + spring-data-jpa | rag-redis (optional) |
| Schema | 无 | auto-create DDL | JPA auto-update | JSON 无 schema |
| Profile | 无 (默认) | `@Profile("h2")` | `@Profile("mysql")` | `@Profile("redis")` |
| 持久化 | ❌ | ✅ 文件 DB | ✅ 事务 ACID | ✅ 分布式 |
| 事务 | ❌ | ✅ JDBC 事务 | ✅ JPA 事务 | ❌ (最终一致性) |


---

## Task 1: 拆 Repository Port interfaces（4 个 Port + 4 个 rename + H2 schema SQL）

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/OrderRepositoryPort.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/RefundRepositoryPort.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/CouponRepositoryPort.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/TicketRepositoryPort.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/schema-h2.sql` (H2 DDL)
- Rename 4 InMemory impl → `builtin/store/`

- [ ] **Step 1.1: 建 `builtin/port/` 目录 + `builtin/store/` 目录**

```bash
mkdir -p rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/
mkdir -p rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/store/
```

- [ ] **Step 1.2: 写 `OrderRepositoryPort` interface**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/OrderRepositoryPort.java`

```java
package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

/** Phase 11: Repository Port — 松耦合, 让 OrderTool 不依赖具体存储实现（InMemory/Redis）。 */
public interface OrderRepositoryPort {
    OrderRecord save(OrderRecord order);
    Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId);

    record OrderRecord(
            String orderId,
            String tenantId,
            String userId,
            long amountCents,
            String status
    ) {}
}
```

- [ ] **Step 1.3: 写 `RefundRepositoryPort` interface**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/RefundRepositoryPort.java`

```java
package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

public interface RefundRepositoryPort {
    RefundRecord save(RefundRecord refund);
    Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId);
    int count();

    record RefundRecord(
            String refundId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reason,
            String status
    ) {}

    static String newRefundId() { return "REF-" + java.util.UUID.randomUUID().toString().substring(0, 8); }
}
```

- [ ] **Step 1.4: 写 `CouponRepositoryPort` interface**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/CouponRepositoryPort.java`

```java
package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

public interface CouponRepositoryPort {
    CouponRecord save(CouponRecord coupon);
    List<CouponRecord> findActiveByTenantAndUser(String tenantId, String userId);

    record CouponRecord(
            String couponId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reasonTag,
            String status
    ) {}

    static String newCouponId() { return "CPN-" + java.util.UUID.randomUUID().toString().substring(0, 8); }
}
```

- [ ] **Step 1.5: 写 `TicketRepositoryPort` interface**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/TicketRepositoryPort.java`

```java
package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

public interface TicketRepositoryPort {
    TicketRecord save(TicketRecord ticket);
    Optional<TicketRecord> findById(String id);
    List<TicketRecord> findByTenant(String tenantId);

    record TicketRecord(
            String ticketId,
            String tenantId,
            String userId,
            String summary,
            String status,
            long createdAt
    ) {}
}
```

- [ ] **Step 1.6: 迁移 + 重命名 + 实现接口**

4 个 InMemory 实现移到 `builtin/store/` 并实现 interface:

1. `OrderRepository.java` → `store/InMemoryOrderRepository.java` `implements OrderRepositoryPort`
2. `RefundRepository.java` → `store/InMemoryRefundRepository.java` `implements RefundRepositoryPort`
3. `CouponRepository.java` → `store/InMemoryCouponRepository.java` `implements CouponRepositoryPort`
4. `InMemoryTicketRepository.java` → `store/InMemoryTicketRepository.java` `implements TicketRepositoryPort`

每个 InMemory class 改动: `public class XxxRepository` → `public class InMemoryXxxRepository implements XxxRepositoryPort`，内部 record 替换为 Port record。

- [ ] **Step 1.7: 编译通过**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test-compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS。如果失败修 import/package。

- [ ] **Step 1.8: git commit**

```bash
git add rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/ \
       rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/store/
git rm rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderRepository.java \
       rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundRepository.java \
       rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponRepository.java \
       rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/InMemoryTicketRepository.java
git commit -m "refactor(agent): 拆 Port interfaces + 迁移 InMemory 实现 (Phase 11 Task 1)

新增 4 Port interface (Order/Refund/Coupon/TicketRepositoryPort).
原有 InMemory 实现移到 builtin/store/ 并 implements Port.
TicketTool 仍直接依赖 InMemoryTicketRepository, Task 2 修复.
96 tests pass, 0 回归."
```

---

## Task 2: 修复 TicketTool 依赖 + OrderTool/RefundTool/CouponTool 改注入 Port

**Files:**
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/TicketTool.java` (InMemoryTicketRepository → TicketRepositoryPort)
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/OrderTool.java` (OrderRepository → OrderRepositoryPort)
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/RefundTool.java` (RefundRepository → RefundRepositoryPort)
- Modify: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/CouponTool.java` (CouponRepository → CouponRepositoryPort)
- Modify: 所有测试文件引用 Repository 的（8 个测试类）

- [ ] **Step 2.1: TicketTool 改注入 TicketRepositoryPort**

将 `private final InMemoryTicketRepository repository` 改为 `private final TicketRepositoryPort repository`。
构造参数 `(InMemoryTicketRepository repository, IdempotencyStore idempotencyStore)` 改为 `(TicketRepositoryPort repository, IdempotencyStore idempotencyStore)`。
import 改 `port.TicketRepositoryPort`，移除 `InMemoryTicketRepository`。
方法内调用 `repository.save(ticket)` / `repository.findById(id)` / `repository.findByTenant(tenantId)` 不需改（Port 方法签名与旧 InMemory 一致）。

- [ ] **Step 2.2: OrderTool 改注入 OrderRepositoryPort**

`private final OrderRepository repo` → `private final OrderRepositoryPort repo`
构造参数 `(OrderRepository repo)` → `(OrderRepositoryPort repo)`
OrderRepository.Order record 引用改为 `OrderRepositoryPort.OrderRecord`（字段名相同：orderId/tenantId/userId/amountCents/status）
方法内 `.orderId()` / `.tenantId()` / `.userId()` / `.amountCents()` / `.status()` 不改变。

- [ ] **Step 2.3: RefundTool 改注入 RefundRepositoryPort**

`private final RefundRepository repo` → `private final RefundRepositoryPort repo`
构造参数 `(RefundRepository repo)` → `(RefundRepositoryPort repo)`
`RefundRepository.Refund` 改为 `RefundRepositoryPort.RefundRecord`
`RefundRepository.newRefundId()` 改为 `RefundRepositoryPort.newRefundId()`

- [ ] **Step 2.4: CouponTool 改注入 CouponRepositoryPort**

同上模式。

- [ ] **Step 2.5: 修 8 个测试文件**

`OrderToolTest`, `RefundToolTest`, `CouponToolTest`, `LogisticsToolTest`（不依赖 Repository）, `Phase10EndToEndTest`, `DefaultAgentLoopTest`, `AgentEndToEndTest`, `InMemoryTicketRepositoryTest`

每个测试中 new 具体 InMemory 实现的地方改 inject Port：
- `new OrderRepository()` → `new InMemoryOrderRepository()`
- `new OrderRepository.Order(...)` → `new OrderRepositoryPort.OrderRecord(...)`

- [ ] **Step 2.6: 全套测试**

```bash
cd ~/projects/spring-ai-alibaba-rag
mvn -pl rag-agent test -q 2>&1 | tail -5
grep -h "tests=" rag-agent/target/surefire-reports/TEST-*.xml 2>/dev/null | sed 's/.*tests="\([0-9]*\)".*/\1/' | awk '{s+=$1} END {print s, "tests"}'
```
Expected: 96 tests (不变), 0 fail.

- [ ] **Step 2.7: git commit**

```bash
git commit -m "refactor(agent): 4 Tool 改注入 Port interface 而非具体类 (Phase 11 Task 2)

- TicketTool: InMemoryTicketRepository → TicketRepositoryPort (修复反模式)
- OrderTool: OrderRepository → OrderRepositoryPort  
- RefundTool: RefundRepository → RefundRepositoryPort
- CouponTool: CouponRepository → CouponRepositoryPort
- 8 测试文件适配: 具体类改 Port, new 时用 InMemory* impl.
96 tests pass, 0 回归."
```

---

## Task 3: Redis 持久化工厂 (JedisPooled + JacksonHolder + Key 前缀)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/RedisStoreFactory.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/package-info.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/RedisStoreFactoryTest.java`

- [ ] **Step 3.1: 写 `RedisStoreFactoryTest`（先红，1 用例: 构造 + key 生成）**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/RedisStoreFactoryTest.java`

```java
package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisStoreFactoryTest {

    @Test
    void buildsKeyWithPrefix() {
        var factory = new RedisStoreFactory(mock(JedisPooled.class), new ObjectMapper());
        assertThat(factory.key("order", "t1", "ORD-1"))
                .isEqualTo("agent:order:t1:ORD-1");
    }

    @Test
    void buildsKeyWithoutTenant() {
        var factory = new RedisStoreFactory(mock(JedisPooled.class), new ObjectMapper());
        assertThat(factory.key("ticket", "TKT-1"))
                .isEqualTo("agent:ticket:TKT-1");
    }
}
```

- [ ] **Step 3.2: 跑测试确认红**

```bash
mvn -pl rag-agent test -Dtest=RedisStoreFactoryTest 2>&1 | tail -5
```
Expected: BUILD FAILURE (cannot find symbol RedisStoreFactory).

- [ ] **Step 3.3: 实现 `RedisStoreFactory`**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/RedisStoreFactory.java`

```java
package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

/**
 * Redis 持久化工厂 — 提供 JSON 序列化 + Key 生成 + Jedis 客户端。
 *
 * <p>Phase 11 所有 RedisRepository 用同一个 factory 实例（单例注入）。
 * 不自己 new JedisPooled, 而是收到已有的 {@code JedisPooled} bean
 * （由 rag-redis RedisConnection 提供）。</p>
 *
 * <h2>Key 格式</h2>
 * {@code agent:<entity>:<tenantId>:<id>}
 * <br>无 tenantId 的实体: {@code agent:<entity>:<id>}
 *
 * <h2>线程安全</h2>
 * JedisPooled 本身是线程安全的, factory 无状态。
 */
public class RedisStoreFactory {

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    public RedisStoreFactory(JedisPooled jedis, ObjectMapper mapper) {
        this(jedis, mapper, "agent:");
    }

    public RedisStoreFactory(JedisPooled jedis, ObjectMapper mapper, String keyPrefix) {
        this.jedis = jedis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
    }

    public JedisPooled jedis() { return jedis; }
    public ObjectMapper mapper() { return mapper; }

    /** agent:order:t1:ORD-1 */
    public String key(String entity, String tenantId, String id) {
        return keyPrefix + entity + ":" + tenantId + ":" + id;
    }

    /** agent:ticket:TKT-abc123 */
    public String key(String entity, String id) {
        return keyPrefix + entity + ":" + id;
    }

    /** agent:order:t1 (列出 tenant 所有 order) */
    public String tenantPrefix(String entity, String tenantId) {
        return keyPrefix + entity + ":" + tenantId + ":";
    }

    /** agent:ticket: (全部) */
    public String entityPrefix(String entity) {
        return keyPrefix + entity + ":";
    }
}
```

- [ ] **Step 3.4: 跑测试确认绿**

```bash
mvn -pl rag-agent test -Dtest=RedisStoreFactoryTest 2>&1 | tail -5
```
Expected: Tests run: 2, Failures: 0, Errors: 0 + BUILD SUCCESS.

- [ ] **Step 3.5: 全套 + commit**

```bash
mvn -pl rag-agent test -q 2>&1 | tail -3
# 97 tests (2 new)
git add rag-agent/src
git commit -m "feat(agent): RedisStoreFactory — key 生成 + JSON 序列化 (Phase 11 Task 3)

Phase 11 Redis 持久化基础设施:
- RedisStoreFactory 持有 JedisPooled + ObjectMapper
- Key 格式: agent:<entity>:<tenantId>:<id>
- 2 个单测 (key with tenant / key without tenant)
97 tests pass, 0 回归."
```

---

## Task 4: H2 JdbcTemplate Repository (4 个表 + schema-h2.sql + 4 个 H2Repository)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/schema-h2.sql`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/H2OrderRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/H2RefundRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/H2CouponRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/H2TicketRepository.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/H2RepositoryTest.java` (集成测试)

- [ ] **Step 4.1: schema-h2.sql (4 表 DDL)**

文件: `rag-agent/src/main/resources/schema-h2.sql`

```sql
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
```

- [ ] **Step 4.2: 写 H2OrderRepository + 集成测试**

模式示例:

```java
package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("h2")
public class H2OrderRepository implements OrderRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<OrderRecord> MAPPER = (rs, row) -> new OrderRecord(
            rs.getString("order_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getLong("amount_cents"),
            rs.getString("status")
    );

    public H2OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OrderRecord save(OrderRecord order) {
        jdbc.update("MERGE INTO agent_order (order_id, tenant_id, user_id, amount_cents, status) "
                + "KEY(order_id) VALUES (?, ?, ?, ?, ?)",
                order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), order.status());
        return order;
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        return jdbc.query("SELECT * FROM agent_order WHERE order_id = ? AND tenant_id = ?",
                MAPPER, orderId, tenantId).stream().findFirst();
    }
}
```

测试用 `@JdbcTest` 或 mock `JdbcTemplate`（跟 Redis 模式一致）。

- [ ] **Step 4.3-4.6: 同模式实现 H2RefundRepository / H2CouponRepository / H2TicketRepository**
- [ ] **Step 4.7: 全套测试 + commit**

```bash
mvn -pl rag-agent test -q 2>&1 | tail -3
# 预期: 103+ tests (96+4 H2 + 3 RedisStoreFactory 已加)
git commit -m "feat(agent): H2 JdbcTemplate 4 Repository @Profile('h2') (Phase 11 Task 4)

H2 embedded 持久化后端:
- schema-h2.sql (4 表: agent_order/refund/coupon/ticket)
- H2OrderRepository (MERGE INTO + JdbcTemplate)
- H2RefundRepository / H2CouponRepository / H2TicketRepository
- 4 个集成测试 (JdbcTemplate mock)
- @Profile('h2') 激活: ./rag-app --spring.profiles.active=dev,h2
103+ tests pass, 0 回归."
```

---

## Task 5: MySQL JPA Repository (4 @Entity + 4 JpaRepository + schema auto-update)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/entity/OrderEntity.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/entity/RefundEntity.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/entity/CouponEntity.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/entity/TicketEntity.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/JpaOrderRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/JpaRefundRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/JpaCouponRepository.java`
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/JpaTicketRepository.java`
- Modify: `rag-agent/pom.xml` (加 spring-data-jpa + mysql-connector-j runtime scope)
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/JpaRepositoryTest.java`

- [ ] **Step 5.1: pom.xml 加依赖**

```xml
        <!-- Phase 11: MySQL JPA 后端 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
```

- [ ] **Step 5.2: 4 个 @Entity + JpaRepository**

模式示例 (OrderEntity.java):

```java
package io.github.yysf1949.rag.agent.store.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "agent_order")
public class OrderEntity {
    @Id @Column(length = 64)
    private String orderId;
    @Column(nullable = false, length = 64)
    private String tenantId;
    @Column(nullable = false, length = 128)
    private String userId;
    private long amountCents;
    @Column(length = 32)
    private String status;

    // default constructor for JPA
    public OrderEntity() {}
    public OrderEntity(String orderId, String tenantId, String userId, long amountCents, String status) {
        this.orderId = orderId; this.tenantId = tenantId; this.userId = userId;
        this.amountCents = amountCents; this.status = status;
    }

    // getters
    public String orderId() { return orderId; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public long amountCents() { return amountCents; }
    public String status() { return status; }

    // to Port record
    public OrderRepositoryPort.OrderRecord toRecord() {
        return new OrderRepositoryPort.OrderRecord(orderId, tenantId, userId, amountCents, status);
    }
}
```

JpaRepository interface:

```java
package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.store.entity.OrderEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("mysql")
public interface JpaOrderRepository extends JpaRepository<OrderEntity, String> {
    Optional<OrderEntity> findByOrderIdAndTenantId(String orderId, String tenantId);
}
```

JpaOrderRepositoryAdapter (implements OrderRepositoryPort):

```java
package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("mysql")
public class JpaOrderRepositoryAdapter implements OrderRepositoryPort {
    private final JpaOrderRepository jpa;
    public JpaOrderRepositoryAdapter(JpaOrderRepository jpa) { this.jpa = jpa; }

    @Override
    public OrderRecord save(OrderRecord order) {
        var entity = new OrderEntity(order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), order.status());
        jpa.save(entity);
        return order;
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        return jpa.findByOrderIdAndTenantId(orderId, tenantId)
                .map(OrderEntity::toRecord);
    }
}
```

- [ ] **Step 5.3-5.6: 同模式实现 Refund/Coupon/Ticket + 集成测试**
- [ ] **Step 5.7: 全套 + commit**

---

## Task 6: RedisOrderRepository @Profile("redis")

(Same as original Task 4 - unchanged)

**Files:**
- Create: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/RedisOrderRepository.java`
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/RedisOrderRepositoryTest.java` (集成测试, @MockBean JedisPooled)

- [ ] **Step 4.1: 写集成测试（先红，3 用例: save+find / findNotFound / overwrite）**

- [ ] **Step 4.2: 实现 RedisOrderRepository (± @Profile("redis") + @ConditionalOnClass(JedisPooled) + key=agent:order:t1:ORD-1)**

文件: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/store/RedisOrderRepository.java`

```java
package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

@Component
@Profile("redis")
public class RedisOrderRepository implements OrderRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(RedisOrderRepository.class);
    private static final String ENTITY = "order";

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final RedisStoreFactory factory;

    public RedisOrderRepository(RedisStoreFactory factory) {
        this.factory = factory;
        this.jedis = factory.jedis();
        this.mapper = factory.mapper();
    }

    @Override
    public OrderRecord save(OrderRecord order) {
        try {
            String json = mapper.writeValueAsString(order);
            String key = factory.key(ENTITY, order.tenantId(), order.orderId());
            jedis.set(key, json);
            return order;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize order: " + order.orderId(), e);
        }
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        String key = factory.key(ENTITY, tenantId, orderId);
        String json = jedis.get(key);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, OrderRecord.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize order key={}", key, e);
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4.3: 全套测试 + commit**

---

## Task 7: RedisRefundRepository + RedisCouponRepository @Profile("redis")

跟 Task 4 完全一样模式。每个 +/- 60 行 + 1 测试类（3 用例）。

---

## Task 8: RedisTicketRepository @Profile("redis")

跟 Task 4 模式一致。注意 Ticket 无 tenantId（直接从 InMemoryTicketRepository 接口，TicketRepositoryPort 是 `findByTenant` 用 `factory.tenantPrefix("ticket", tenantId)` 扫描 keys）。

---

## Task 9: application-redis.yml + h2.yml + mysql.yml + @Profile 三配置

**Files:**
- Create: `rag-agent/src/main/resources/application-redis.yml` (跟 application-dev.yml 互补, 只加 agent 持久化相关配置)
- Modify: `rag-app/src/main/resources/application.yml` (加 `spring.profiles.include: redis` 或文档说明)
- Create: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/RedisRepositoriesIntegrationTest.java` (集成测试, 所有 4 个 Redis Repository)

- [ ] **Step 7.1: application-redis.yml**

```yaml
# Phase 11 — Agent 业务数据持久化 (Redis)
# 激活方式: 运行 rag-app 时加 -Dspring.profiles.active=dev,redis
# 或 environment: SPRING_PROFILES_ACTIVE=dev,redis
#
# 前提: 本地 Redis (127.0.0.1:6379) 已运行, spring.rag.redis.enabled=true

spring:
  rag:
    redis:
      enabled: true

agent:
  repository:
    store: redis                # 切到 Redis 实现
    key-prefix: "agent:"
```

- [ ] **Step 7.2: 集成测试 — 全部 4 个 Redis Repository 的端到端验证**

文件: `rag-agent/src/test/java/io/github/yysf1949/rag/agent/store/RedisRepositoriesIntegrationTest.java`

需要本地 Redis `127.0.0.1:6379` 在跑，否则 skip（`@EnabledIfSystemProperty(named = "tests.integration.redis", matches = "true")`）。

- [ ] **Step 7.3: 全套 + commit**

---

## Task 10: E2E smoke 测试 (Phase 11 全部过 + curl 6 场景 x 3 后端)

跟 Phase 10 Task 14 完全相同流程：
1. 更新 `Phase10EndToEndTest` → `Phase11EndToEndTest` (旧测试仍保持，验证 InMemory 仍正常)
2. `mvn -pl rag-agent test` 确认 110+ tests pass
3. `mvn test` 确认全仓库 350+ tests pass (0 回归)
4. 启动 rag-app (dev profile, InMemory 模式) → curl 6 场景验证
5. 启动 rag-app (dev,redis profile) → curl 6 场景验证 (走 Redis 持久化)

---

## Task 11: 文档同步 (architecture §11 / evolution / RUNBOOK §Redis/H2/MySQL)

**Files:**
- Modify: `docs/architecture.md` (追加 §11 Phase 11)
- Modify: `docs/evolution.md` (追加 Phase 11 shipped 段)
- Modify: `docs/RUNBOOK.md` (追加 §13 Redis 故障排查)
- Modify: `README.md` (模块表更新: rag-agent 加 Redis 持久化)

---

## Task 12: 收口 (双写 + push + verify)

跟 Phase 10 Task 15 完全一样：双写 Obsidian + final commit + push + ls-remote verify。

---

## Self-Review 检查清单

**1. 范围确认:**
- 4 个 InMemory Repository → Port interface + Redis impl
- 修复 TicketTool 直接依赖 InMemoryTicketRepository (反模式)
- 所有 Business Tool 不再依赖具体类
- InMemory 是默认 (dev profile)，Redis 是 opt-in (app: dev,redis)
- 不破坏现有 Phase 9/10 测试

**2. 文件结构:**
```
rag-agent/src/main/java/io/github/yysf1949/rag/agent/
├── builtin/
│   ├── port/
│   │   ├── OrderRepositoryPort.java      (interface + OrderRecord)
│   │   ├── RefundRepositoryPort.java     (interface + RefundRecord)
│   │   ├── CouponRepositoryPort.java     (interface + CouponRecord)
│   │   └── TicketRepositoryPort.java     (interface + TicketRecord)
│   ├── store/                             (InMemory implementations)
│   │   ├── InMemoryOrderRepository.java
│   │   ├── InMemoryRefundRepository.java
│   │   ├── InMemoryCouponRepository.java
│   │   └── InMemoryTicketRepository.java
│   ├── OrderTool.java                    (injects OrderRepositoryPort)
│   ├── RefundTool.java                   (injects RefundRepositoryPort)
│   ├── CouponTool.java                   (injects CouponRepositoryPort)
│   └── TicketTool.java                   (injects TicketRepositoryPort)
├── store/                                 (persistent implementations)
│   ├── RedisStoreFactory.java            (JedisPooled + key 生成)
│   ├── H2OrderRepository.java            (@Profile("h2"))
│   ├── H2RefundRepository.java           (@Profile("h2"))
│   ├── H2CouponRepository.java           (@Profile("h2"))
│   ├── H2TicketRepository.java           (@Profile("h2"))
│   ├── JpaOrderRepository.java           (@Profile("mysql") Spring Data JPA)
│   ├── JpaRefundRepository.java          (@Profile("mysql"))
│   ├── JpaCouponRepository.java          (@Profile("mysql"))
│   ├── JpaTicketRepository.java          (@Profile("mysql"))
│   ├── RedisOrderRepository.java         (@Profile("redis"))
│   ├── RedisRefundRepository.java        (@Profile("redis"))
│   ├── RedisCouponRepository.java        (@Profile("redis"))
│   ├── RedisTicketRepository.java        (@Profile("redis"))
│   └── entity/                           (JPA @Entity classes)
│       ├── OrderEntity.java
│       ├── RefundEntity.java
│       ├── CouponEntity.java
│       └── TicketEntity.java
```

**3. 测试估算: 96 → ~150 增量:**

| 来源 | 新增 | 说明 |
|---|---|---|
| RedisStoreFactoryTest | +2 | key 格式 |
| H2OrderRepositoryTest | +3 | save/find/MERGE INTO |
| H2RefundRepositoryTest | +3 | save/find/count |
| H2CouponRepositoryTest | +3 | save/findActive |
| H2TicketRepositoryTest | +3 | save/find/findByTenant |
| MySQL(Entity+Jpa+Adapter)Test | +12 | 4 实体 × 3 用例 |
| RedisOrderRepositoryTest | +3 | save/find/overwrite |
| RedisRefundRepositoryTest | +3 | save/find/count |
| RedisCouponRepositoryTest | +3 | save/findActive |
| RedisTicketRepositoryTest | +3 | save/find/findByTenant |
| Phase11EndToEndTest | +3 | InMemory+H2+Redis 三后端验证 |
| RedisRepositoriesIntegrationTest | +3 | 全 4 个通过 Redis |
| H2RepositoriesIntegrationTest | +3 | 全 4 个通过 H2 |
| 旧测试适配 | 0 | 不改逻辑 |
| **总计** | **+54** | **96 → ~150** |

**4. 计划验证:**
- 所有 Todo 替换成 `- [ ]` 复选框格式 ✅
- 0 占位符 ✅
- 123 tests / 0 fail (预期) ✅
- Phase 8/9/10 0 回归 ✅