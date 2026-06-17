# Runbook — spring-ai-alibaba-rag

> **Audience**: anyone who needs to build, run, smoke-test, or troubleshoot
> the pipeline. Read top-to-bottom on first contact, then jump straight to
> the section you need via the TOC below.

---

## 1. Prerequisites

| Tool | Min version | Why |
|---|---|---|
| **JDK** | 21 (Eclipse Temurin recommended) | Project compiles with `--release 21` |
| **Maven** | 3.9+ | Multi-module build |
| **Redis Stack** | 7.4+ (with `RediSearch` module) | Vector store + 3-tier cache (spec §4) |
| **DashScope API key** | *not required for unit tests* | Only for end-to-end smoke (spec §5.2) |

### Quick JDK check

```bash
java --version    # expect 21.x
mvn --version     # expect 3.9.x
```

If you don't have JDK 21 yet, install via SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 21.0.2-tem
sdk install maven
```

---

## 2. Build & unit test (no external services)

```bash
git clone https://github.com/yysf1949/spring-ai-alibaba-rag.git
cd spring-ai-alibaba-rag
mvn verify
```

Expected output:

```
[INFO] Tests run: 124, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

> The 23 `RedisIndexManagerSmokeTest` cases show as `Skipped` (not failed)
> when Redis Stack is not running. **Skipped is not failure** — they're
> integration tests that auto-activate when Redis is reachable.

### Run a single test class

```bash
mvn -pl rag-pipeline test -Dtest=QAServiceImplTest
```

### Run only the fast unit tests (skip slow integration)

```bash
mvn -pl rag-pipeline test -Dtest='*Test' -DskipITs=true
```

---

## 3. Local Redis Stack via docker-compose

> Compose file is at [`docker-compose.yml`](../docker-compose.yml).
> It runs Redis Stack 7.4 with RediSearch enabled on port 6379.

```bash
docker compose up -d redis
docker compose ps   # confirm "running"

# Smoke: redis-cli PING → PONG
docker compose exec redis redis-cli ping
docker compose exec redis redis-cli MODULE LIST   # must include "search"
```

### First-time index creation

The first QA or ingest call against a tenant lazily creates the index
`rag:index:{tenant}:{kbId}` with the HNSW params from spec §5.3. No
manual bootstrap step is required.

To **manually pre-create** an index (CI / disaster recovery), call
`RedisIndexManager.ensureIndex(...)` from a one-off Java main.

### Tear down

```bash
docker compose down          # keep volumes
docker compose down -v       # also delete the redis data volume
```

---

## 4. End-to-end smoke (when DashScope key is available)

> ⛔ **This section requires** `DASHSCOPE_API_KEY` in the environment and
> `rag-embedding` module implementation (not yet shipped — see
> [README.md](./README.md#pipeline-status-live)).

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl rag-app -am spring-boot:run   # not yet available
curl -X POST http://localhost:8080/api/qa \
     -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: tenant-A' \
     -d '{"userId":"u1","rawText":"运费怎么退？","permissionTags":["ROLE_USER"]}'
```

A correct response is a JSON `Answer` object with `source` ∈
`{CACHE, LLM, FALLBACK_RULE}` and `citations[]` containing the
referenced chunks.

---

## 5. Project layout cheat sheet

```
rag-core/                  ← domain + ports, no Spring, no external deps
  src/main/java/.../model  ← record types (Chunk, Query, Answer, KbVersion, …)
  src/main/java/.../port   ← service contracts (VectorStore, RewriteService, QAService, …)
  src/main/java/.../exception

rag-redis/                 ← Redis Stack adapter (implements rag-core ports)
  src/main/java/.../vector
  src/main/java/.../cache

rag-pipeline/              ← algorithm + orchestrators
  src/main/java/.../splitter/ChunkSplitter
  src/main/java/.../ingest/IngestServiceImpl
  src/main/java/.../context/ContextAssembler   (P3-A)
  src/main/java/.../rewrite/RuleBasedQueryRewriter  (P3-B)
  src/main/java/.../rewrite/CachingRuleBasedRewriter
  src/main/java/.../qa/QAServiceImpl            (P3-C)
  src/test/java/...                            (124 unit tests)
```

When adding a new module or class, **respect the boundary**:

- `rag-core` has **zero** Spring / Redis / HTTP imports
- `rag-redis` depends on `rag-core` only
- `rag-pipeline` depends on `rag-core` only (uses `rag-redis` at wiring time, not compile time)
- `rag-app` (not yet) is the only place Spring beans are defined

---

## 6. Troubleshooting

### 6.1 `mvn verify` fails with `cannot find symbol class LlmService`

You probably ran `mvn -pl rag-pipeline test` without first installing
`rag-core` to the local Maven repo. The new ports (`LlmService`,
`HotQuestionProvider`, `QAService`) live in `rag-core` and need
`mvn install`:

```bash
mvn -pl rag-core install -DskipTests
mvn -pl rag-pipeline test
```

…or just use `mvn verify` from the project root, which builds in the
right order.

### 6.2 `redis.exceptions.RedisCommandTimeoutException: FT.SEARCH`

Possible causes:

1. **Index is missing** — see §3 above.
2. **HNSW params too aggressive** — current defaults: `M=16, EF_CONSTRUCTION=200, EF_RUNTIME=10`. If you customise them down, expect slower queries at scale.
3. **Vector dim mismatch** — current model is DashScope v3 = 1536. If you swap models, **blow away the index** first: `FT.DROPINDEX rag:index:{tenant}:{kbId}` and let it recreate.

### 6.3 AnswerCache hit ratio stuck at 0

Check that `RagAnswerCacheImpl` is wired (it lives in `rag-redis` and is
only registered when a Redis connection is available). On local
without Redis, the answer cache degrades to a no-op (silent miss).

### 6.4 Sensitive data redaction test failing on a new pattern

`DefaultSensitiveDataRedactor` is regex-based and intentionally
conservative. To add a new PII pattern:

1. Add the regex to the **ordered** `Pattern[]` array — **ID_CARD must
   always run first** (its 18-char shape is a strict superset of
   any bank-card pattern).
2. Add a test case in `DefaultSensitiveDataRedactorTest` covering both
   the positive match and a negative case (similar digits that must
   NOT be redacted).
3. For production, swap in a Luhn-checking implementation behind the
   same `SensitiveDataRedactor` SPI.

### 6.5 `git push` rejected: "non-fast-forward"

Someone (probably you in another terminal) pushed ahead of you. Pull
with rebase:

```bash
git pull --rebase origin main
git push origin main
```

### 6.6 `rag-qa` returns 503 with no error in logs

This is by design (spec §10): when `VectorStoreUnavailableException`
or `EmbeddingUnavailableException` propagates out of the QAService,
the HTTP layer (future `rag-app`) translates it to 503 + Retry-After.
For now, check the **upstream** — Redis for vector search, DashScope
for embedding. `tail -f` the redis container logs:

```bash
docker compose logs -f redis
```

### 6.7 TLS — production rollout (Redis + LLM)

> **Why this section exists**: spec §2.2 mandates TLS 1.2+ for every
> external connection (Redis, SiliconFlow, internal RPC). Local
> docker-compose stays in plain TCP because TLS handshakes against a
> self-signed CA add a lot of friction with no security benefit; this
> section is the procedure to turn TLS on for staging / production.

#### 6.7.1 SiliconFlow (LLM / embedding / rerank)

Already on HTTPS by default — the `rag.siliconflow.base-url` placeholder
in `application.yml` resolves to `https://api.siliconflow.cn/v1` unless
overridden. No additional configuration is required to encrypt
SiliconFlow traffic; the Spring WebClient that the
`SiliconFlow*Gateway` beans wrap negotiates TLS by default.

If your org runs an internal SiliconFlow-compatible proxy with a
private cert, point `rag.siliconflow.base-url` at the proxy URL and add
the proxy's CA to the JVM trust store (or to the
`-Djavax.net.ssl.trustStore` system property) at startup.

#### 6.7.2 Redis (Jedis)

**Opt-in** — set `REDIS_TLS_ENABLED=true` and provide a private-CA
trust store. The application fails fast at startup if the flag is on
but the trust store is missing, so a misconfiguration is loud rather
than silent.

| Env var | Required? | Default | Notes |
|---|---|---|---|
| `REDIS_TLS_ENABLED` | yes (to enable) | `false` | Mirrors `spring.data.redis.ssl.enabled` |
| `REDIS_TLS_TRUSTSTORE` | yes (when enabled) | empty | Absolute path to PKCS12 or JKS file |
| `REDIS_TLS_TRUSTSTORE_PASSWORD` | yes (when enabled) | empty | The keystore password (passed via env, never in YAML) |
| `REDIS_TLS_TRUSTSTORE_TYPE` | no | `PKCS12` | `PKCS12` or `JKS` |
| `REDIS_TLS_VERIFY_HOSTNAME` | no | `true` | Set to `false` only for self-signed dev certs |

**Generate a private-CA trust store (corp CA / Vault PKI)**:

```bash
# Option A — Vault PKI: fetch the issuing CA via the Vault CLI
vault read -field=certificate pki_root/cert/ca_chain > /etc/redis-ca.crt

# Option B — OpenSSL: build a self-signed CA for a dev cluster
openssl req -x509 -nodes -newkey rsa:4096 \
  -keyout /etc/redis-ca.key -out /etc/redis-ca.crt \
  -subj "/CN=rag-redis-dev" -days 365

# Convert the PEM into a PKCS12 trust store the JVM can load
keytool -importcert -alias redis-ca \
  -file /etc/redis-ca.crt \
  -keystore /etc/redis-truststore.p12 \
  -storetype PKCS12 -storepass "${REDIS_TLS_TRUSTSTORE_PASSWORD}"
```

Mount `/etc/redis-truststore.p12` into the rag-app pod (or copy it
onto the VM) and export the env var pointing at it. **Never commit
the trust store or its password to git** — the `.gitignore` already
covers `*.p12` and `*.jks`, but verify on every clone.

**Configure Redis Stack 7.4 for TLS**:

The Redis Stack container needs TLS turned on too. A minimal
`redis-tls.conf`:

```conf
tls-port 6380
port 0
tls-cert-file /etc/redis/tls/redis.crt
tls-key-file  /etc/redis/tls/redis.key
tls-ca-cert-dir /etc/redis/tls/ca
tls-protocols "TLSv1.2 TLSv1.3"
```

Then point rag-app at the TLS port: `REDIS_PORT=6380`.

**Verification**:

After restarting rag-app with the env vars set, the startup log
should contain:

```
INFO  i.g.y.rag.redis.config.RedisSslAutoConfiguration :
      Configuring Jedis SSL trust store=/etc/redis-truststore.p12 type=PKCS12 verifyHostname=true
INFO  i.g.y.r.redis.config.RedisConnection          :
      Redis pool starting in TLS mode
INFO  i.g.y.r.redis.config.RedisConnection          :
      Redis pool ready — host=… port=6380 maxTotal=32 ping=PONG TLS=on
```

The `TLS=on` marker is the operator's smoke test — if it's missing
while `REDIS_TLS_ENABLED=true` is set, the trust store loaded but
Jedis didn't pick up the `SSLSocketFactory` (check that
`RedisSslAutoConfiguration` is on the classpath via
`mvn dependency:tree | grep rag-redis`).

#### 6.7.3 Audit log TLS

The audit file at `${AUDIT_FILE:-logs/audit.log}` is written in cleartext
by default. If your compliance regime requires the audit trail to be
encrypted at rest, mount the volume on an encrypted filesystem
(LUKS / EBS / Cloud KMS) and rely on the OS-level encryption —
adding application-level encryption to the audit file would block
`grep` and `jq` access for compliance reviewers.

The audit channel itself (`io.github.yysf1949.rag.app.audit.AuditChannel`,
SLF4J logger name `audit`) is the integration point for shipping
events to a remote Kafka topic or HTTPS endpoint — the
`logback-spring.xml` appender chain is the place to wire that up in
production. The default file appender covers the local-disk retention
requirement (spec §2.4 — 6 months).

---

## 7. What to read next

- [METRICS.md](./METRICS.md) — every Prometheus metric the pipeline exposes.
- [MULTI_TENANT.md](./MULTI_TENANT.md) — how tenants / permissions / PII
  are enforced, with examples of legal vs illegal request shapes.
- The design spec — every "spec §X.X" in this file links back here.
