# Phase 7 — Close Spec Gaps: Ingest API, Micrometer, Docker, RefundRule E2E

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Close the highest-priority gaps between the design spec and the current implementation. After this plan ships, the spec's Definition of Done (§16) is fully achievable, the application is Prometheus-observable, and the refund-rule demo runs as an automated test.

**Architecture:** Five independent clusters, each ships as a single commit (5 commits total). Each cluster is small enough to land in 1 PR review round. Clusters are sequenced so a green test suite at the end of each cluster gives the next cluster a stable foundation.

**Tech Stack:** Spring Boot 3.3, Micrometer 1.14 (`MeterRegistry` from `spring-boot-starter-actuator`), `springdoc-openapi-starter-webmvc-ui` (already in pom.xml), `testcontainers-redis` 1.20.4, JUnit 5 + Mockito.

---

## Decisions (拍板 2026-06-17, user picked option-2)

1. **Q1 — Sequencing**: ✅ All 5 clusters (1+2+3+4+5)
2. **Q2 — Testcontainers**: ✅ Testcontainers if available, fallback `localhost:6379` (probed at execution time)
3. **Q3 — RefundRule LLM**: ✅ Stub LLM (no key needed); real LLM is Cluster 3b follow-up
4. **Q4 — Execution style**: ✅ Cluster 1+2 via subagent (subagent-driven-development), Cluster 3+4+5 inline

**Execution order**: 1 → 2 → 3 → 4 → 5. Each cluster = 1 commit on main. Total ~5 commits.

---

## Cluster 1 — `POST /api/ingest` HTTP endpoint (closes spec §6.3, §16 DoD)

### Task 1.1: Write failing controller tests

**Files**:
- Create: `rag-app/src/test/java/io/github/yysf1949/rag/app/web/IngestControllerTest.java`

**Step 1**: Create the test file with 4 failing tests covering:
- `POST /api/ingest` with valid body → 202 + `jobId` in body
- `POST /api/ingest` with missing `tenantId` header → 401
- `GET /api/ingest/{jobId}` returns the job snapshot (PENDING / READY / FAILED)
- `POST /api/ingest/{jobId}/publish` promotes a READY job to PUBLISHED

**Step 2**: Run `mvn -pl rag-app test -Dtest=IngestControllerTest` — expect **COMPILATION FAILURE** (no `IngestController` class).

**Step 3**: Implement the controller.

**Step 4**: Run again — expect **4/4 PASS**.

**Step 5**: Commit.

### Task 1.2: Implement `IngestController`

**Files**:
- Create: `rag-app/src/main/java/io/github/yysf1949/rag/app/web/IngestController.java`

**Request DTO** (public static class inside controller):
```java
public static class IngestRequest {
    @NotBlank public String tenantId;
    @NotBlank public String kbId;
    @NotBlank public String documentId;
    @NotNull  public Long documentVersion;
    @NotBlank public String title;
    @NotBlank public String sourceUri;
    public List<String> permissionTags;
    @NotEmpty public List<Document.Section> sections;
}
public static class Section {  // public static class
    @NotBlank public String path;
    @NotBlank public String content;
}
```

**Endpoints**:
```java
@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    private final IngestService ingest;
    public IngestController(IngestService ingest) { this.ingest = ingest; }

    @PostMapping
    public ResponseEntity<IngestJob> submit(
        @RequestHeader(MdcTenantFilter.HEADER_TENANT) String tenant,
        @Valid @RequestBody IngestRequest body) {
        var doc = new Document(
            tenant, body.kbId, body.documentId, String.valueOf(body.documentVersion),
            body.title, body.sourceUri,
            Set.copyOf(body.permissionTags == null ? List.of() : body.permissionTags),
            body.sections);
        IngestJob job = ingest.ingestAsync(doc);
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/{jobId}")
    public IngestJob get(@PathVariable String jobId) {
        return ingest.getJob(jobId).orElseThrow(() ->
            new IngestJobNotFoundException("Unknown jobId=" + jobId));
    }

    @PostMapping("/{jobId}/publish")
    public IngestJob publish(@PathVariable String jobId) {
        return ingest.publish(jobId);
    }

    public static class IngestJobNotFoundException extends RuntimeException { ... }
}
```

**Tenant resolution**: header authoritative, body's `tenantId` ignored (mirrors `RagController`).

### Task 1.3: Add `IngestJobNotFoundException` handler (RFC 7807 404)

**Files**:
- Modify: `rag-app/src/main/java/io/github/yysf1949/rag/app/web/RagExceptionHandler.java`

Add a new handler returning 404 with `slug=ingest-job-not-found` and a `Retry-After: 30` header.

### Task 1.4: End-to-end curl verification

```bash
# Submit
curl -s -X POST http://localhost:18081/api/ingest \
  -H "X-Tenant-Id: tenant-A" -H "Content-Type: application/json" \
  -d '{"kbId":"kb-prod-001","documentId":"kb-prod-001/doc-test","documentVersion":10,"title":"测试","sourceUri":"https://x","sections":[{"path":"s1","content":"退款规则说明..."}]}'
# → 202 + jobId

# Poll
curl -s http://localhost:18081/api/ingest/{jobId}
# → eventually READY

# Publish
curl -s -X POST http://localhost:18081/api/ingest/{jobId}/publish
# → 200 + status=PUBLISHED
```

---

## Cluster 2 — Micrometer metrics injection (closes spec §9.1, §16)

### Task 2.1: Write metrics test for QAService

**Files**:
- Modify: `rag-pipeline/src/test/java/io/github/yysf1949/rag/pipeline/qa/QAServiceImplTest.java`

Inject `SimpleMeterRegistry` into the test, run an end-to-end happy-path, assert:
- `rag.qa.requests.total{tenant,source="LLM"}` counter incremented
- `rag.qa.latency.ms{stage=...}` 7 timers created (rewrite, cacheCheck, embed, retrieve, rerank, assemble, generate)
- `rag.qa.retrieved.chunks.count{tenant}` recorded

### Task 2.2: Inject `MeterRegistry` into `QAServiceImpl`

**Files**:
- Modify: `rag-pipeline/src/main/java/io/github/yysf1949/rag/pipeline/qa/QAServiceImpl.java`

Pattern:
```java
public QAServiceImpl(..., MeterRegistry meterRegistry) {
    this.requests = Counter.builder("rag.qa.requests.total")
        .tag("tenant", "{tenant}").register(meterRegistry);
    // ...timers
}
```

Add metric points at:
- End of `answer()`: `requests.increment(source.tag)`
- After each step: `timer.record(elapsed, TimeUnit.MILLISECONDS)`
- `rag.qa.degradation.total{tenant,reason}` incremented in catch blocks

**Allow null MeterRegistry** for backwards-compat:
```java
public QAServiceImpl(..., @Nullable MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
}
```

### Task 2.3: Inject metrics into `IngestServiceImpl`

**Files**:
- Modify: `rag-pipeline/src/main/java/io/github/yysf1949/rag/pipeline/ingest/IngestServiceImpl.java`

Add:
- `rag.ingest.jobs.total{tenant,status}` — incremented on terminal state
- `rag.ingest.chunks.total{tenant}` — incremented by `chunks.size()` on success
- `rag.ingest.duration.ms{tenant}` — timer around `ingestSync`

### Task 2.4: Verify `curl /actuator/prometheus` returns `rag.qa.*` and `rag.ingest.*` metrics

```bash
curl -s http://localhost:18081/actuator/prometheus | grep -E "^rag_"
```

---

## Cluster 3 — `RefundRuleEndToEndTest` (closes spec §11.3, §16)

### Task 3.1: Add eval fixture

**Files**:
- Create: `rag-test/src/test/resources/eval/refund-rule.json`

JSON fixture with 1 document (3 sections: 通用条款 / 申请流程 / 特殊商品) + 1 query + expected substring + expected sourceUri.

### Task 3.2: Write failing test

**Files**:
- Create: `rag-test/src/test/java/io/github/yysf1949/rag/eval/RefundRuleEndToEndTest.java`

Use Testcontainers `RedisContainer` (image `redis/redis-stack-server:7.4.0-v3`) + `@DynamicPropertySource` to override `spring.rag.redis.host/port`.

Test flow:
1. Spin up Redis
2. Boot `@SpringBootTest` with `rag.siliconflow.enabled=false` (use stub gateway + stub rerank + stub LLM)
3. Call `IngestService.ingestSync(doc)` + `IngestService.publish(jobId)`
4. Call `QAService.answer(query)` for the eval query
5. Assert: `answer.finalText().contains("运费退还")` OR answer has ≥ 1 citation with `sourceUri == "https://docs.example.com/refund-policy"`

### Task 3.3: Run + commit

**Note**: This test does NOT need real SiliconFlow (uses stub LLM). It tests the data flow only, not the LLM quality.

---

## Cluster 4 — Docker + docker-compose (closes spec §12.2, §16)

### Task 4.1: Multi-stage `Dockerfile`

**Files**:
- Create: `Dockerfile` (project root)

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml ./
COPY rag-core rag-core
COPY rag-embedding rag-embedding
COPY rag-redis rag-redis
COPY rag-pipeline rag-pipeline
COPY rag-app rag-app
COPY rag-test rag-test
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl rag-app -am -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre
COPY --from=build /src/rag-app/target/rag-app-*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
```

### Task 4.2: Add `app` service to `docker-compose.yml`

**Files**:
- Modify: `docker-compose.yml`

```yaml
  app:
    build: .
    container_name: rag-app
    depends_on:
      redis:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: docker
      REDIS_HOST: redis
      REDIS_PORT: 6379
      SERVER_PORT: 8080
      RAG_SILICONFLOW_ENABLED: "${RAG_SILICONFLOW_ENABLED:-false}"
      SILICONFLOW_API_KEY: "${SILICONFLOW_API_KEY:-}"
    ports:
      - "18081:8080"
```

### Task 4.3: Verify

```bash
docker compose build app
docker compose up -d app
curl -s http://localhost:18081/actuator/health
# → 200 UP
```

---

## Cluster 5 — MDC stage logging + dashboard JSON (closes spec §9.2)

### Task 5.1: Inject stage timings into MDC during request

**Files**:
- Modify: `rag-pipeline/src/main/java/io/github/yysf1949/rag/pipeline/qa/QAServiceImpl.java`
- Modify: `rag-app/src/main/resources/logback-spring.xml` (add pattern: `%X{stage}`)

After each step, `MDC.put("stage", "rewrite")` / `"embed"` / etc. Clear at the end.

### Task 5.2: Commit + ship

---

## Risks & non-goals

**What this plan covers**: §6.3 ingest API, §9.1 metrics, §11.3 eval test, §12.2 dockerfile, §9.2 MDC (small).

**What this plan does NOT cover** (deferred):
- §7.5 rate limiting (Redis sliding window) — needs `redis-cell` or custom Lua
- §10 Resilience4j circuit breaker — needs `resilience4j-spring-boot3` dep
- §15 JWT auth — needs `spring-security-oauth2-resource-server` dep + real IdP
- §5.1 `rag:session:*` conversation memory — needs cross-request state
- §12.1 K8s manifests — needs cluster + helm
- §5.1 `rag:metrics:{tenant}:{yyyyMMdd}` HINCRBY — needs daily rollup logic
- LLM fallback for RuleBasedQueryRewriter (§7.2) — separate work
- PermissionMode AND↔OR runtime switch via `@ConditionalOnProperty` (§8.2) — config decision

**Risks**:
- Cluster 2: existing 124 pipeline tests may fail if `MeterRegistry` injection breaks the existing constructor signature. Mitigation: overload constructor with `@Nullable` + default.
- Cluster 3: Testcontainers may not be installed in the dev environment. Mitigation: use `@EnabledIfSystemProperty(named="runIT")` gate.
- Cluster 4: `mvnw` may not exist (Maven 3.9.16 is in `~/apache-maven-3.9.16/`). Use `mvn` directly in Dockerfile.

---

## Verification ritual (run after each cluster)

```bash
export JAVA_HOME=~/jdk/jdk-21.0.2
cd ~/projects/spring-ai-alibaba-rag

# 1. Build all modules
mvn -DskipTests package

# 2. Run unit tests
mvn test
# Expect: 177 + new tests, all green

# 3. Run IT (Redis Stack running)
mvn -pl rag-app -am test -Dtest='*IT' -DrunIT=true
# Expect: RagEndToEndIT + new RefundRuleEndToEndIT green

# 4. Smoke
fuser -k 18081/tcp 2>/dev/null
set -a; source rag-embedding/.env; set +a
java -jar rag-app/target/rag-app-0.1.0-SNAPSHOT.jar --server.port=18081 &
sleep 5
curl -s http://localhost:18081/actuator/health
curl -s http://localhost:18081/actuator/prometheus | grep -c '^rag_'

# 5. Commit + push
git push origin main
```
