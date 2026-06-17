# Lessons Learned — spring-ai-alibaba-rag

> Dev diary of real issues encountered during Phases 5-P4 → 7 local-development,
> rebuild, deploy, and end-to-end verification. Each entry has: symptom → root
> cause → fix. Entries are ordered by when they were hit.

---

## 1. Duplicate `@SpringBootApplication` — two `main()` entry points

**Symptom**: Two classes in `rag-app` (`RagAppApplication.java` + `IngestRunner.java`)
both carry `@SpringBootApplication` + `main()`. If not caught at compile time, the
Spring Boot repackage plugin picks one arbitrarily; at runtime the other may still
be loaded as a context candidate.

**Root cause**: `IngestRunner.java` was left over from an earlier prototyping phase.
It was written as a standalone `@SpringBootApplication` to do one-shot ingest +
publish at startup. After the HTTP layer was added (`RagAppApplication`), it became
a duplicate.

**Fix**: Delete `IngestRunner.java`. The ingest capability is called programmatically
through `IngestService` — it does not need its own main class.

**Lesson**: Any `@SpringBootApplication` / `main()` in a multi-module Maven project
must be unique per runnable artifact. Use `spring-boot-maven-plugin`'s `<mainClass>`
explicitly when there's ambiguity. Stub / one-shot runners belong in the test source
tree or as a separate module.

---

## 2. `.env` bare `export` line breaks dotenv loading

**Symptom**: The `.env` file had a standalone `export SPRING_APPLICATION_JSON` line
with no `=value` assignment. Some dotenv parsers treat this as setting the variable
to empty string, silently overriding the actual `SPRING_APPLICATION_JSON` set on the
next line.

**Root cause**: Sloppy copy-paste — someone wrote `export KEY` thinking it would
"re-export" an already-set variable, but in dotenv format it's meaningless and
destructive.

**Fix**: Delete the bare `export` line.

**Lesson**: `.env` files should contain only `KEY=VALUE` lines. Never `export KEY`
alone, never `export KEY=VALUE` (the `export` prefix is non-standard and parser-
dependent). Keep `.env` clean and minimal — every line should do one thing.

**Plus**: The `.env` is untracked (gitignored), but still visible to anyone who
runs `cat`. Use `.env.example` for documentation and check that the example doesn't
have the same issues.

---

## 3. `javac` missing — Maven can't compile

**Symptom**:
```
[ERROR] Fatal error compiling: error: release version 21 not supported
```
Even though `java -version` reports OpenJDK 21.

**Root cause**: The system install was the **JRE only** (`openjdk-21-jre`), not the
JDK (`openjdk-21-jdk`). The `javac` compiler was not on disk, so Maven's compiler
plugin failed.

```bash
# System has:
dpkg -l | grep openjdk  # → openjdk-21-jre, default-jre (no -jdk)

# javac is absent:
which javac       # → nothing
ls /usr/lib/jvm/java-21-openjdk-amd64/bin/javac  # → No such file
```

**Fix**: Point `JAVA_HOME` at a full JDK that exists elsewhere on the system:
```bash
export JAVA_HOME=~/jdk/jdk-21.0.2
```

**Lesson**: Always verify `javac` separately from `java`. A running JVM does not
guarantee a JDK is installed. For development machines, install the JDK explicitly:
`sudo apt-get install openjdk-21-jdk` (requires sudo, which may not be available in
all environments — have a portable JDK tarball as fallback).

**Prevention**: Add a shell check to the startup script:
```bash
if ! command -v javac &>/dev/null; then
    echo "javac not found — set JAVA_HOME to a JDK, not a JRE"
    exit 1
fi
```

---

## 4. QA endpoint returns 503 — `vector-store-unavailable`

**Symptom**:
```json
POST /api/qa → 503
{"title": "vector-store-unavailable", "detail": "Vector store is currently unavailable."}
```
Health endpoint (`/actuator/health`) returns 200, Redis Stack container is running.

**Root cause** (two sub-problems):

### 4a. Missing publish pointer (first request without `kbVersion`)
When the request body does not include `kbVersion`, the code path is:
```
QAServiceImpl.retrieve() → kbId = query.kbVersion() == null ? null : ...
RedisVectorStore.search() → resolveActiveVersion(client, tenantId, kbId, -1L)
                          → client.get("rag:publish:" + tenantId + ":" + kbId)
                          → "rag:publish:tenant-A:null"  ← key doesn't exist
                          → throws VectorStoreUnavailableException
```
The request must include both `kbId` and `version` in the `kbVersion` object.

### 4b. Version mismatch (publish pointer `1` ≠ chunk version `5`)
Even with a valid `kbVersion` in the request, search returned empty:
- Redis chunks have `documentVersion: 5` (set by the IngestRunner which used `kbVersion = 5L`)
- The publish pointer `rag:publish:tenant-A:kb-prod-001` was `1`
- The RediSearch pre-filter `@documentVersion:[-inf 1]` excludes all chunks (5 > 1)

**Fix**:
```bash
# Align publish pointer with actual chunk versions
docker exec rag-redis-stack redis-cli SET "rag:publish:tenant-A:kb-prod-001" "5"
```

**Lesson**: The publish pointer is the authoritative source of "what's live." After
any data ingest, always verify:
1. The chunks were written with the intended version
2. The publish pointer was updated to that version
3. A direct `FT.SEARCH` with the application's own filter produces results

The version mismatch is a design smell: the publish pointer and the chunk version
are logically coupled but stored independently. Consider adding an invariant check
in `publish()` that verifies all promoted chunks have the correct version before
swapping the alias.

---

## 5. Empty retrieval despite data in Redis

**Symptom**:
```json
POST /api/qa → 200
{"source": "FALLBACK_RULE", "finalText": "抱歉，知识库中没有找到与您问题相关的内容。", "retrieved": []}
```
But `FT.SEARCH` on the alias directly returns 3 chunks.

### 5a. Missing `permissionTags` in request
The pre-filter in `buildPreFilter()` has:
```java
if (userTags == null || userTags.isEmpty()) {
    sb.append(" @permissionTags:{__no_such_tag_4242__}");
    return sb.toString();
}
```
Without `permissionTags` in the request body, the filter matches nothing.

**Fix**: Always pass `"permissionTags": ["public"]` in the QA request.

**Lesson**: The "no tags = no results" semantics is intentional (user has no
authority = no data visible), but it's surprising on first use. The API doc
must explicitly document this requirement.

### 5b. Missing `kbVersion` in request
Even after fixing the publish pointer, a request without `kbVersion.kbId` still
goes through the `kbId=null` path described in 4a.

**Fix**: Pass `"kbVersion": {"kbId": "kb-prod-001", "version": 5}`.

---

## 6. End-to-end QA request shape (working)

After all fixes, the working curl is:

```bash
curl -X POST http://localhost:18081/api/qa \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-A" \
  -d '{
    "userId": "u-7782",
    "rawText": "退款规则是什么？",
    "kbVersion": {"kbId": "kb-prod-001", "version": 5},
    "permissionTags": ["public"]
  }'
```

Response: 200 OK, 3 chunks retrieved, reranked, LLM-generated Chinese answer
(SiliconFlow `Qwen/Qwen2.5-7B-Instruct`). Subsequent identical requests hit
the answer cache (40ms, source=CACHE).

---

## 7. Deployment process (local dev)

Step-by-step to boot the full stack from scratch:

```bash
# 0. Prerequisites
docker compose up -d redis          # Redis Stack with RediSearch
export JAVA_HOME=~/jdk/jdk-21.0.2   # Full JDK, not JRE

# 1. Build
mvn package -pl rag-app -am -DskipTests

# 2. Source env (SiliconFlow API key etc.)
set -a; source rag-embedding/.env; set +a

# 3. Boot
java -jar rag-app/target/rag-app-0.1.0-SNAPSHOT.jar --server.port=18081

# 4. Verify health
curl -s http://localhost:18081/actuator/health   # → 200

# 5. Verify QA (requires published data)
curl -s -X POST .../api/qa ...                    # → 200 + answer

# 6. Troubleshoot Redis data
docker compose exec redis redis-cli KEYS "rag:*"
docker compose exec redis redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" \
  "@tenantId:{tenant\-A} @kbId:{kb\-prod\-001} @status:{ACTIVE}" LIMIT 0 3
docker compose exec redis redis-cli GET "rag:publish:tenant-A:kb-prod-001"
```

### Smoke checklist

| Check | Command | Expected |
|---|---|---|
| Redis | `docker compose ps` | `rag-redis-stack` Up |
| Redis module | `docker exec rag-redis-stack redis-cli MODULE LIST` | `search` loaded |
| Health | `curl -s localhost:18081/actuator/health` | `{"status":"UP"}` |
| Publish ptr | `redis-cli GET rag:publish:tenant-A:kb-prod-001` | numeric, matches chunk version |
| Alias | `redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" "*" LIMIT 0 0` | ≥ 1 result |
| QA e2e | curl as above | 200, non-empty finalText |

---

## 8. Redis operational pitfalls

This project uses **Redis Stack** (RediSearch module) for the vector store and
3-tier cache. Several non-obvious issues come up during development and debugging.

### 8a. Container name ≠ Docker Compose service name

The `docker-compose.yml` defines:

```yaml
services:
  redis:                          # ← service name
    container_name: rag-redis     # ← container_name (may be overridden by Compose)
```

But `docker ps` shows the actual container name as `rag-redis-stack` (Docker Compose
v2 appends the project directory name). Trying `docker exec rag-redis` fails with:

```
Error: no container with name or ID "rag-redis" found: no such container
```

**Fix**: Always use `docker compose ps` to discover the actual container name, or
use `docker exec $(docker compose ps -q redis) redis-cli PING` to target by service.

**Lesson**: Never hard-code container names in scripts. Use `docker compose exec`
(the correct Compose command) instead of raw `docker exec`:

```bash
# ✅ Correct — resolves the real container name automatically
docker compose exec redis redis-cli PING

# ❌ Fragile — breaks if Compose renames the container
docker exec rag-redis-stack redis-cli PING
```

### 8b. TAG field escaping in RediSearch CLI queries

RediSearch TAG fields treat characters like `-`, `.`, `:` as metacharacters.
When querying ad-hoc via `redis-cli FT.SEARCH`, they must be escaped with `\\-`:

```bash
# ❌ Fails — '-' in tenant-A is a TAG delimiter
redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" \
  "@tenantId:{tenant-A}" LIMIT 0 3
# → Syntax error at offset 19 near A

# ✅ Works — escape dashes
redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" \
  "@tenantId:{tenant\\-A} @kbId:{kb\\-prod\\-001} @status:{ACTIVE}" LIMIT 0 3
```

The application's `RedisVectorStore.escapeTag()` handles this internally (it
escapes `-`, `.`, `/`, `:`, `{`, `}`, `|`, `,`, `<`, `>`, ` `). Any manual
`redis-cli` debugging needs the same escaping.

**Lesson**: Always use `\\-` for TAG values containing hyphens in `redis-cli`.
When copying filter expressions from application logs, add the escaping manually.

### 8c. Vector index structure

Understanding the Redis key schema is essential for debugging:

| Key pattern | Type | Purpose |
|---|---|---|
| `rag:chunk:{tenant}:{chunkId}` | HASH | Chunk data (text, embedding binary, tags) |
| `rag:index:{tenant}:{version}` | FT.INDEX | RediSearch index for a specific KB version |
| `rag:index:{tenant}:{version}-staging` | FT.INDEX | Staging index (before publish) |
| `rag:active:{tenant}:{kbId}` | FT.ALIAS | Alias that always points to the current live index |
| `rag:publish:{tenant}:{kbId}` | STRING | Current published version number |
| `rag:cache:tenant:{tenant}:{hash}` | STRING | Answer cache (serialized Answer JSON) |
| `rag:embedding-cache:{textHash}` | BINARY | Embedding vector cache (float32 array) |

The search flow:
1. `resolveActiveVersion()` reads `rag:publish:{tenant}:{kbId}` to get the version
2. The `rag:active:{tenant}:{kbId}` alias resolves to a specific `rag:index:{tenant}:{version}`
3. The KNN query runs against that index, filtered by the pre-filter

**Quick debug commands**:

```bash
# List all indices
redis-cli FT._LIST

# Show index schema + info
redis-cli FT.INFO "rag:index:tenant-A:5-staging" | head -30

# Count docs in an alias
redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" "*" LIMIT 0 0

# Check publish pointer
redis-cli GET "rag:publish:tenant-A:kb-prod-001"

# Dump a specific chunk (text fields only, embedding is binary)
redis-cli HGETALL "rag:chunk:tenant-A:c78c3cb4..."

# See all cache entries
redis-cli KEYS "rag:cache:*"
```

### 8d. `publishedAt: 0` — epoch zero is intentional

Chunks stored in Redis have `publishedAt: 0` (epoch, 1970-01-01). This is not a
bug — the `publishedAt` field is a numeric timestamp set atomically during publish.
A value of `0` means the chunk was never explicitly set (legacy data from before
the publish step started populating this field).

The RediSearch pre-filter `@publishedAt:[-inf now]` includes epoch-zero chunks
because `0 ≤ now`. This is correct: legacy chunks are always visible.

### 8e. Staging vs Active indices

The two-phase publish workflow (spec §5.2):
1. **STAGING** chunks are written to `rag:index:{tenant}:{v}-staging`
2. **Publish** atomically: creates the active index, flips all STAGING chunks
   to ACTIVE status, swaps the alias, writes the publish pointer

To check staging data before publish:
```bash
redis-cli FT.SEARCH "rag:index:tenant-A:5-staging" "*" LIMIT 0 0
```

### 8f. Common Redis debug checklist

When the QA endpoint returns unexpected results, run these in order:

```bash
# 1. Is Redis up?
docker compose exec redis redis-cli PING
# → PONG

# 2. Is RediSearch loaded?
docker compose exec redis redis-cli MODULE LIST
# → 1) name: search, ver: 999999

# 3. Does the alias exist and have data?
docker compose exec redis redis-cli FT.SEARCH \
  "rag:active:tenant-A:kb-prod-001" "*" LIMIT 0 0
# → N (count), not error

# 4. Does the publish pointer match the chunk versions?
docker compose exec redis redis-cli GET \
  "rag:publish:tenant-A:kb-prod-001"
# → Must match documentVersion of chunks in the index

# 5. Can a direct search with the app's own filter find chunks?
docker compose exec redis redis-cli FT.SEARCH \
  "rag:active:tenant-A:kb-prod-001" \
  "@tenantId:{tenant\\-A} @kbId:{kb\\-prod\\-001} \
   @status:{ACTIVE} @documentVersion:[-inf 5]" \
  LIMIT 0 3
# → Returns chunks (may need version ceiling adjustment)

# 6. Are there any cached answers that might return stale data?
docker compose exec redis redis-cli KEYS "rag:cache:*"
# → Clear with: redis-cli DEL <key> or redis-cli FLUSHDB (dev only)
```

### 8g. Ingest without an HTTP endpoint

This project does not expose an ingest HTTP API — data is ingested programmatically
via `IngestService`. The (now-deleted) `IngestRunner` was a one-shot CLI approach.
To ingest new data at runtime, either:

- Write a `CommandLineRunner` bean that calls `IngestService.ingestSync()` + `publish()`
- Add a `POST /api/ingest` endpoint to `RagController`
- Directly write chunk hashes to Redis and set the publish pointer manually
  (not recommended — bypasses the embedding pipeline and RediSearch indexing)

---

## 9. Spring Boot bean configuration traps

### 9a. Duplicate `SiliconFlowProperties` registration

**Symptom**: ApplicationContext starts with two `SiliconFlowProperties` beans
(one from `@EnableConfigurationProperties` on the class, one from a redundant
`@Bean siliconFlowProperties()` method). Spring throws `BeanDefinitionOverrideException`
or silently keeps one.

**Root cause**: I wrote both:

```java
@Configuration
@EnableConfigurationProperties(SiliconFlowProperties.class)   // ← registers bean A
public class SiliconFlowAutoConfiguration {
    @Bean
    public SiliconFlowProperties siliconFlowProperties() {    // ← registers bean B
        return new SiliconFlowProperties();
    }
}
```

Spring Boot 2.1+ allows this when `spring.main.allow-bean-definition-overriding=true`,
but it's a code smell. The `@Bean` method is redundant and shadows the
auto-config-registered one.

**Fix**: Delete the `@Bean` method. `@EnableConfigurationProperties` is sufficient.

**Lesson**: Pick one mechanism. Either
- `@EnableConfigurationProperties(Foo.class)` + the class is auto-registered
- `@ConfigurationProperties` + `@ConfigurationPropertiesScan` (component scan)
- `@Bean` factory method

Mixing them is a debugging rabbit hole.

### 9b. `@ConditionalOnMissingBean` lets stubs win the race

**Symptom**: SiliconFlow adapters don't activate even though `rag.siliconflow.enabled=true`
and the key is set. The runtime beans are the **stub** implementations (16-dim embeddings).

**Root cause**: This pattern on the SiliconFlow beans:

```java
@Bean
@ConditionalOnMissingBean(EmbeddingGateway.class)   // ← "only register if no bean exists"
public EmbeddingGateway siliconFlowEmbeddingGateway(...) { ... }
```

The `EmbeddingStubConfig` in `rag-app` was already creating a stub `EmbeddingGateway`
bean unconditionally. `@ConditionalOnMissingBean` saw the stub and **skipped**
the SiliconFlow bean. Same race for `RerankService` and `LlmService`.

**Fix**:
- Remove `@ConditionalOnMissingBean` from individual beans
- Move the conditional to the **class level** (`@Configuration`):
  ```java
  @Configuration
  @Conditional(SiliconFlowEnabledCondition.class)   // class-level only
  public class SiliconFlowAutoConfiguration {
      @Bean
      @Primary                                      // ← safety belt
      public EmbeddingGateway siliconFlowEmbeddingGateway(...) { ... }
  }
  ```
- Add `@Primary` on each bean as a belt-and-suspenders measure

**Lesson**: `@ConditionalOnMissingBean` is for "fall back to this default if user
didn't bring their own." When you want a conditional adapter to **override** existing
beans, the conditional must be on the `@Configuration` class, not individual beans.
The semantics invert: class-level = "include this whole config or none of it",
bean-level = "skip just this one if a peer already exists."

### 9c. `System.getenv()` doesn't propagate to forked JVMs

**Symptom**: `@Conditional` reads `System.getenv("RAG_SILICONFLOW_API_KEY")` and sees
`null` in the running Spring Boot process — even though `echo $VAR` shows the value
in the launching shell.

**Root cause**: The launching script does:

```bash
set -a; source .env; set +a    # exports to current shell
mvn spring-boot:run             # Maven spawns a forked JVM
                                # forked JVM inherits env, but if someone runs
                                # `mvn -pl rag-app` it may not
```

Some Maven plugins (`maven-toolchains-plugin`, `exec-maven-plugin`, or `spring-boot:run`
under `-Dfork=false`) launch JVMs without inheriting env vars. `System.getenv()` returns
null in those cases.

**Fix**: Don't rely on `System.getenv()` in production code. Use Spring's
`Environment.getProperty()` — it reads from many sources (system env, JVM system
properties, application.yml, `SPRING_APPLICATION_JSON`, command-line args).

**For tricky envs** (env var not resolving through YAML `${VAR}` placeholder), use
`SPRING_APPLICATION_JSON` which Spring reads before any other source:

```bash
export SPRING_APPLICATION_JSON='{"rag":{"siliconflow":{"api-key":"...","enabled":true}}}'
```

**Lesson**: Spring's property resolution order is your friend — but you need to know it.
Order (highest to lowest priority): `SPRING_APPLICATION_JSON` > command-line `--key=val` >
JVM `-Dkey=val` > OS env vars > `application.yml`. Place the value in the highest
priority that your environment supports.

### 9d. YAML `${VAR:default}` is a literal string if `VAR` is unset

**Symptom**: `env.getProperty("rag.siliconflow.api-key")` returns the literal
`${SILICONFLOW_API_KEY:}` — including the placeholder syntax — instead of empty
string or null.

**Root cause**: YAML placeholder `${SILICONFLOW_API_KEY:}` IS the literal value when
the env var is not set. Spring's `PropertySourcesPropertyResolver` returns the
unresolved placeholder text. Reading it via `Environment.getProperty()` gives you
back the placeholder, not null or empty.

**Debug it**:
```java
String v = env.getProperty("rag.siliconflow.api-key");
log.info("raw value=[{}]", v);  // → raw value=[${SILICONFLOW_API_KEY:}]
log.info("isBlank={}", v == null || v.isBlank());  // → false
```

**Fix**: Either:
1. Set the env var so the placeholder resolves
2. Use a regex or `contains("${")` check to detect unresolved placeholders
3. Inject `ConfigurableEnvironment` and call `propertyResolver.resolvePlaceholders(...)`
   explicitly

**Lesson**: YAML placeholder syntax is "best effort" — it does not guarantee the
env var is set. Always validate placeholder resolution in your `@Conditional`.

### 9e. `List.of(String).contains(enumValue)` is always false

**Symptom**: Test asserts `assertTrue(List.of("LLM").contains(answer.source()))` —
fails because `Answer.source()` is an `AnswerSource` enum, not a String.

**Root cause**: `List<String>.contains(Object)` calls `equals()`, but the list's
generic type erasure means the list can hold anything. The enum `AnswerSource.LLM`
is not equal to the String `"LLM"`.

**Fix**:
```java
// ❌ false
assertTrue(List.of("LLM").contains(answer.source()));

// ✅ true
assertEquals(AnswerSource.LLM, answer.source());

// ✅ if you need a List
assertTrue(List.of(AnswerSource.LLM).contains(answer.source()));
```

**Lesson**: Be explicit about types in tests. When the production code returns
an enum, assert against the enum value, not its `.name()` string.

---

## 10. Process & environment traps

### 10a. `.env` brace expansion breaks `source`

**Symptom**: `source .env` produces
```
{siliconflow:: command not found
```

**Root cause**: Someone wrote the `SPRING_APPLICATION_JSON` line in `.env` like:
```
SPRING_APPLICATION_JSON={siliconflow:{...}}
```
Bash's `source` doesn't parse `.env` files — it executes them as shell scripts. The
`{siliconflow:...}` triggers bash's brace expansion: it tries to run a command
called `siliconflow:` and gets `command not found`.

**Fix**: Quote the JSON value so bash doesn't try to expand braces:
```
SPRING_APPLICATION_JSON='{"siliconflow":{"enabled":true,"api-key":"sk-..."}}'
```

**Lesson**: The `.env` file format is a de-facto standard that **most loaders** (Python
`dotenv`, Node `dotenv`, Ruby) parse with `KEY=VALUE` rules. But **bash `source` is not
a `.env` loader** — it executes the file as shell. Always quote values containing
shell metacharacters (`{`, `}`, `$`, `` ` ``, `;`, `&`, `|`, `<`, `>`).

**Safer pattern**:
```bash
# Use a real dotenv loader:
set -a
eval "$(cat .env | sed 's/^/export /' | sed 's/=/="/' | sed 's/$/"/')"
set +a
```
Or use a tool like `direnv` / `dotenv` / `python-dotenv` CLI.

### 10b. Two main classes = Maven "Unable to find a single main class"

**Symptom**:
```
[ERROR] Unable to find a single main class from the following candidates
    [RagAppApplication, IngestRunner]
```

**Root cause**: Two classes in the same module carry `@SpringBootApplication` +
`main()`. The `spring-boot-maven-plugin` repackage step can't pick one.

**Fix**: Either:
- Delete one of the main classes (preferred when one is throwaway)
- Specify the main class explicitly in the POM:
  ```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <mainClass>io.github.yysf1949.rag.app.RagAppApplication</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
  ```

**Lesson**: A multi-module project may have multiple `@SpringBootApplication` classes
across modules (e.g., a test app + a real app), but **never two in the same module**
unless they're in different source roots (`src/main` vs `src/test`).

### 10c. Port already in use — Hermes bridge vs Spring Boot

**Symptom**: Spring Boot starts then exits:
```
Web server failed to start. Port 8080 was already in use.
```

**Root cause**: Another process owns port 8080. In this environment, the Hermes
`bridge` process listens on 8080 for the web gateway.

**Fix**: Pick a different port. Convention in this workspace:
- 18081 — Spring Boot rag-app (siliconflow)
- 8080 — Hermes bridge (do not touch)

```bash
# Either:
java -jar app.jar --server.port=18081
# Or via env:
export SERVER_PORT=18081
# Or via Spring's config:
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18081"
```

**Lesson**: `ss -tlnp` (or `netstat -tlnp`) before binding. Add a port-conflict
detection in your startup script:
```bash
if ss -tln | grep -q ":$PORT "; then
    echo "Port $PORT already in use:"
    ss -tlnp | grep ":$PORT "
    exit 1
fi
```

### 10d. IngestRunner in `src/test/java` isn't compiled into the JAR

**Symptom**: After writing `IngestRunner.java` in `rag-app/src/test/java/...`,
`mvn spring-boot:run` doesn't see it.

**Root cause**: `src/test/java` is only compiled during the `test` phase, never
included in the production JAR. Use `src/main/java` for any class that must be
runnable via `mvn` or `java -jar`.

**Fix**: Move to `src/main/java/...`. If the class is one-shot, mark it clearly.

**Lesson**: Source root = scope:
- `src/main/java` — production code, packaged in JAR
- `src/test/java` — test code, not in JAR
- `src/main/resources` — config / templates / static
- `src/test/resources` — test-only resources

### 10e. `parseKbVersion` time-based version, not literal `1`

**Symptom**: `IngestRunner` hard-codes `version = "1"` for the document. The
`publish` step throws `VectorStoreUnavailableException` because the active index
expects version 3 (computed from the current date).

**Root cause**: `parseKbVersion` derives a version number from the current date
(some pattern like `(epochDay - baseline) / interval` or similar). Ingest writes
chunks with `documentVersion = "1"`, but the publish step looks for
`rag:index:{tenant}:3` (the version `parseKbVersion` computed from today's date).

The two paths must agree on the version. The chunk's `documentVersion` and the
target index version must match.

**Fix**: Compute the version in `IngestRunner` using the same logic as
`parseKbVersion`, then pass it as the document's version.

**Or simpler**: Use a high, fixed version that the publish step won't conflict
with (e.g., `5L`), and set `rag:publish:{tenant}:{kbId}` to that version after
publish. This is acceptable for dev/test, not production.

**Lesson**: Versioning schemes that depend on a clock create hidden coupling.
Two code paths that produce the "same" logical value from different inputs will
drift. Document the version-generation function as part of the public API.

### 10f. Embedding dim mismatch — stub 16-dim, index 1536-dim

**Symptom**:
```
Could not add vector with blob size 64 (expected size 6144)
```
(64 = 16 floats × 4 bytes; 6144 = 1536 floats × 4 bytes)

**Root cause**: The HNSW index was created with `dim=1536` (production default
for DashScope), but the stub embedding gateway returns 16-dim vectors. RediSearch
rejects the dimension mismatch on HSET.

**Fix**: Parameterize the index dim from `spring.rag.embedding.dim`:
- Default: 16 (matches stub)
- Override in `application.yml` or `SPRING_RAG_EMBEDDING_DIM` env var: 1024 (bge-m3),
  1536 (DashScope text-embedding-v3)

```java
RedisIndexManager.DEFAULT_DIM = env.getProperty("spring.rag.embedding.dim", Integer.class, 16);
```

**Lesson**: Always derive vector index parameters from the actual embedding model.
Hard-coding production numbers and overriding them in dev is a classic source
of "works on my machine" bugs.

### 10g. `extractKbId` fallback to `default-kb` breaks kbId-specific queries

**Symptom**: Test queries for `kbId="kb-it"` return 0 results because the
ingest path wrote to `default-kb`.

**Root cause**: The `extractKbId` helper splits `documentId` on `/` to get the
kbId. When the input has no `/`, it falls back to `"default-kb"`. Test data
that uses `"kb-it"` (no `/`) gets routed to `default-kb` instead of the intended
`kb-it`. The test then queries for `kb-it` and finds nothing.

**Fix**: Test data must use the format `kb-id/some-doc-id` (e.g., `"kb-it/doc-it"`).

**Lesson**: Defaults that "do something sensible" can hide intent. Either:
- Make the default explicit (require a kbId parameter)
- Or change the parser to fail loudly on missing separator

### 10h. `Allow bean definition overriding` set to true in dev — dangerous

**Symptom**: With `spring.main.allow-bean-definition-overriding=true` (often the
default in `spring-boot-devtools` or older Spring Boot 2.x), duplicate bean
definitions silently overwrite each other instead of throwing.

**Root cause**: Production should NOT allow this. In dev, you can debug faster
because you don't see the override error.

**Fix**: Set explicitly:
```yaml
spring:
  main:
    allow-bean-definition-overriding: false   # default in Spring Boot 2.1+
```

Or in `application.yml` for tests only.

**Lesson**: "Fail fast" is the right default. If you have a configuration conflict,
you want Spring to tell you at startup, not silently use the last-defined bean
and surprise you at runtime.

---

## 11. Spec vs implementation gap

Cross-referencing the design spec ([`2026-06-16-spring-ai-alibaba-rag-design.md`](./superpowers/specs/2026-06-16-spring-ai-alibaba-rag-design.md))
against the current code, these are the gaps at the time of writing this document:

### ✅ Spec sections fully implemented

| Spec § | Description | Status |
|---|---|---|
| §2 | Tech stack (Spring Boot 3.3, Java 21, Redis Stack 7.4) | ✅ |
| §5.1 | Redis key naming (`rag:chunk:*`, `rag:index:*`, `rag:active:*`, `rag:publish:*`) | ✅ |
| §5.2 | Chunk metadata schema (HASH fields, TAG/NUMERIC/VECTOR types) | ✅ |
| §5.3 | HNSW params (M=16, EF_CONSTRUCTION=200, EF_RUNTIME=10, COSINE) | ✅ |
| §6.2 | ChunkSplitter (sliding window, 200-800 tokens, 50 overlap) | ✅ |
| §7 | QAService 8-step chain | ✅ |
| §7.2 | RuleBasedQueryRewriter | ✅ |
| §7.3 | RerankService interface + impl (stub + SiliconFlow) | ✅ |
| §7.4 | ContextAssembler (token budget, PII redaction, metadata preservation) | ✅ |
| §7.5 | 7-tier degradation ladder | ✅ |
| §8 | Multi-tenant + permission filter | ✅ |
| §8.3 | PII redaction (Chinese ID, mobile, bank card Luhn) | ✅ |
| §10 | Error handling (exceptions → HTTP status) | ✅ (handlers) |
| §13.12 | REST endpoint `POST /api/qa` with OpenAPI 3 + RFC 7807 | ✅ |

### ⚠️ Spec sections partially implemented

| Spec § | Description | Status | Gap |
|---|---|---|---|
| §6.1 | Async ingest with staging + publish | ⚠️ partial | `IngestService.ingestAsync` exists, but no HTTP endpoint exposes it; in-memory `IngestJobRepositoryImpl` instead of Redis-backed |
| §7.2 | LLM fallback in QueryRewriter | ⚠️ partial | Only RuleBased; LLM fallback stubbed but not implemented |
| §9.1 | Micrometer metrics (13 metrics listed) | ⚠️ missing | `Answer.metrics` carries the per-stage timing as a Map, but **no `MeterRegistry` is injected** — metrics are not published to Prometheus. Spec §16 DoD requires `/actuator/prometheus` to expose them. |
| §9.2 | MDC logging (stage, retrieved, etc.) | ⚠️ partial | `MdcTenantFilter` sets tenantId/userId/sessionId. Stage timings in `Answer.metrics` but **not in MDC** during request handling. |
| §9.3 | Eval set + offline CI gate | ❌ missing | No `rag-test/src/test/resources/eval/` directory; no `Recall@K` / `Grounded Rate` measurements |
| §11.2 | Testcontainers integration tests | ⚠️ partial | `RedisVectorStoreSmokeTest` uses `@EnabledIfSystemProperty(named = "runIT")`; 23 tests skip without it. No `RagEndToEndIT` against Testcontainers — current `RagEndToEndIT` uses a hard-coded `localhost:6379` Redis. |
| §11.3 | Real-case demo (`RefundRuleEndToEndTest`) | ⚠️ partial | IngestRunner deleted (we did the equivalent end-to-end via curl); no automated JUnit test asserting "退款规则问答" returns "运费退还" with `sourceUri` citation |
| §12.1 | Evolution path: local → production → K8s | ❌ missing | Only local docker-compose; no K8s manifests, no Helm chart, no multi-instance scaling |
| §12.2 | `docker-compose.yml` with **both** redis and app | ❌ missing | Only redis service; no `app` service, no `build:` directive |
| §12.3 | K8s probes (liveness/readiness/startup) | ❌ missing | `/actuator/health/liveness` and `/readiness` are exposed by Spring Boot but not exercised; no PodDisruptionBudget, no startup probe config |
| §15 | Tenant authentication (JWT, gateway trust) | ⚠️ stub | `X-Tenant-Id` header is **trusted as-is** (no signature verification). Suitable for dev only. Production needs JWT or mTLS at the gateway. |
| §16 | DoD: `mvn clean verify` all green | ✅ | 177 tests + 23 Redis smoke + 1 IT pass |
| §16 | DoD: `curl -X POST /ingest` | ❌ | No `POST /api/ingest` endpoint exists; ingest is programmatic-only |
| §16 | DoD: `curl -X POST /qa` returns cited answer | ✅ | Verified end-to-end at `port 18081`; output is via LLM, citation structure present |

### ❌ Spec sections not implemented (Phase 7+ backlog)

| Spec § | Description | Suggested phase |
|---|---|---|
| §10 | Resilience4j circuit breaker for Redis + SiliconFlow | Phase 7 (resilience) |
| §5.1 | `rag:session:{tenant}:{userId}:{sessionId}` conversation summary | Phase 8 (memory) |
| §5.1 | `rag:metrics:{tenant}:{yyyyMMdd}` daily HINCRBY counter | Phase 7 (metrics) |
| §11.3 | Automated `RefundRuleEndToEndTest` (real LLM) | Phase 6-D6 (test coverage) |
| §12.1 | K8s Deployment + Service + Ingress | Phase 7 (deployment) |
| §12.1 | Multi-instance test (2 app + Sentinel/Cluster Redis) | Phase 7 (HA) |
| §15 | JWT / OIDC auth, gateway-trust model | Phase 8 (security) |
| §7.5 | Rate limiting (Redis sliding window) | Phase 7 (rate limit) |

### How to close the gaps

The work in this section is the natural next phase (Phase 7). Prioritization:

1. **`POST /api/ingest` endpoint** — without it, no real ingestion flow possible
   from outside the JVM. 2-3 hours of work.
2. **Micrometer metrics** — add `MeterRegistry` bean, inject counters/timers into
   QAServiceImpl + IngestServiceImpl. 4-6 hours.
3. **`docker-compose.yml` with app + redis** — small change, big quality-of-life
   win. 1 hour.
4. **`RefundRuleEndToEndTest`** — single test class that does ingest + query +
   asserts "运费退还" substring. 2 hours.
5. **K8s manifests** — Deployment + Service + ConfigMap + Secret. 4-6 hours.

Items 1-4 are the minimum to call the spec "implemented." Item 5+ is the
"production-ready" follow-up.

---

## 12. Phase 7 lessons — three concrete patterns hit while shipping clusters 1-3

### 12.1 Cluster 3: Document.documentId encodes kbId — "kbId/documentId" not just "documentId"

`IngestServiceImpl.extractKbId(compositeDocumentId)` does `indexOf('/')` and
returns the prefix as `kbId`. If you pass `documentId="doc-refund-v1"`, the
extract returns `"default-kb"` and your `publish()` flips the active index
for the wrong KB. Your subsequent QA call will get
`VectorStoreUnavailableException` because `kb-refund` was never published.

**Fix** — encode the kbId into the documentId:

```java
new Document(
    tenantId, KB_ID, KB_ID + "/doc-refund-v1", "1",
    title, sourceUri, ...)
```

This is a **code smell**: `Document` carries `kbId` as a separate field, but
`extractKbId()` re-derives it from `documentId` instead of using the field.
Should be fixed by adding `kbId` to `IngestJob` at job-creation time so the
publish path doesn't need to re-parse. Tracked as a P2 follow-up — out of
scope for cluster 3 which is just the eval test.

### 12.2 Cluster 3: Stub gateway (16-dim) is incompatible with production index (1024-dim)

`StubEmbeddingGateway.DIM = 16` but `RedisIndexManager.DEFAULT_DIM = 1024`.
The plan §3.2 said "use stub gateway" — that would have crashed RediSearch
on the first `FT.ADD` because the vector width didn't match the schema.

The eval test **must** use real SiliconFlow embedding. Gate it with
`@EnabledIfEnvironmentVariable("SILICONFLOW_API_KEY", ".+")` so it skips
gracefully when no key is set. The plan got this wrong; we caught it
during plan-vs-reality sweep because `StubEmbeddingGateway.DIM` was
visibly 16 in the source.

**Lesson**: any time an eval test touches the vector store, it needs
production-dim embeddings. The stubs are for unit tests that don't
go near RediSearch.

### 12.3 Cluster 3: Cross-module `@SpringBootTest` needs `classes =` for sibling modules

`@SpringBootTest` does classpath scanning by default — looks for a
`@SpringBootConfiguration` in the same module. When the test lives in
`rag-test` but the `RagAppApplication` lives in `rag-app`, the scanner
finds nothing and fails with:

```
java.lang.IllegalStateException: Unable to find a @SpringBootConfiguration,
you need to use @ContextConfiguration or @SpringBootTest(classes=...) with your test
```

**Fix** — explicitly point at the app class:

```java
@SpringBootTest(classes = RagAppApplication.class, ...)
```

The existing tests in `rag-app/src/test/...` don't need this because the
scanner finds `RagAppApplication` automatically within the same module.

### 12.4 Cluster 1/3: Spring Boot fat-jar shutdown reports `NoClassDefFoundError: ThrowableProxy`

When you `kill <pid>` (SIGTERM) a running `java -jar rag-app-*.jar` instance,
the JVM shuts down via `SpringApplicationShutdownHook`. The shutdown
sequence triggers a last `log.warn(...)` from a thread whose classloader
has already been closed by Spring Boot's `LaunchedURLClassLoader`. Logback
then tries to format the warning and crashes with:

```
NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy
```

This is a **known Spring Boot issue** with fat-jars (the loader closes
classloaders in a specific order during shutdown that races with logback's
final flush). It is NOT a bug in our code — the application already
stopped responding to HTTP at that point.

**Fix options** (none chosen — non-blocking):
- Run as exploded jar (`java -cp 'libs/*' io.github.yysf1949.rag.app.RagAppApplication`)
  — works around the race entirely
- Add `spring-boot-loader-tools` repackaging with `layout = ZIP`
- Suppress with `-Dlogging.register-shutdown-hook=false` (loses graceful-shutdown logs)

### 12.5 Cluster 2: Micrometer — preserve backward-compat constructors when injecting MeterRegistry

QAServiceImpl went from 9-arg → 10-arg constructor when we added
`MeterRegistry`. The existing 23-test `QAServiceImplTest` constructs
`new QAServiceImpl(...)` directly in 4 places. Changing the signature
breaks every one of them.

**Pattern** — keep the old constructor as a delegate:

```java
// Production — real MeterRegistry, full metrics
public QAServiceImpl(..., MeterRegistry meterRegistry) {
    // store meterRegistry, build counters/timers
}

// Test — no-op registry, preserves 9-arg call sites
public QAServiceImpl(...) {  // old signature
    this(..., new SimpleMeterRegistry());
}
```

`SimpleMeterRegistry` is a no-op implementation — perfect for unit tests
that don't care about metrics. Same pattern used for `IngestServiceImpl`
(5-arg → 6-arg with `MeterRegistry`).

### 12.6 Cluster 4: Docker build — 5 pitfalls hit while shipping Dockerfile + compose

#### 12.6.1 Maven base image triggers a CN proxy cert mismatch

`FROM maven:3.9-eclipse-temurin-21` (multi-arch) pulls through
`image-mirror.r2.daocloud.vip` in this environment. The mirror returns
a cert for `wwwqa.microsoft.com` — TLS handshake fails. The single-arch
`eclipse-temurin:21-jdk` image has no such issue.

**Fix** — keep Temurin as the base and COPY Maven in from the build
context (`./.docker-maven/`). The host's existing
`$HOME/apache-maven-3.9.16/` is staged by `scripts/build-docker.sh`.

#### 12.6.2 Build container has no network (or only host-proxy network)

The container running `RUN apt-get install curl && curl ...` can't reach
external mirrors — even ones the host can reach via 127.0.0.1:7897.
The build container's network namespace is isolated.

**Fix** — never try to download Maven / apt packages from inside the
build. Stage everything into the build context and COPY it in.

#### 12.6.3 Podman 4.9 imagebuilder can't parse heredocs in `RUN`

```
RUN cat > settings.xml <<'EOF'
<settings>...
EOF
```

Podman mis-parses the lines after `EOF` as new Dockerfile instructions
(`"<SETTINGS>"` not a valid command). Docker BuildKit handles heredocs
correctly.

**Fix** — use `printf '%s\n' ... > settings.xml` for short XML/SQL/etc.
content. Verbose but works in both Docker and podman.

#### 12.6.4 `.dockerignore` excludes Maven's `*.jar` files too

A naive `.dockerignore` with `**/*.jar` strips out the
`plexus-classworlds-*.jar` from `.docker-maven/boot/`, breaking `mvn`
inside the build. Symptom: `Error: Could not find or load main class
org.codehaus.plexus.classworlds.launcher.Launcher`.

**Fix** — add explicit allow-list negation:
```
**/*.jar
!/.docker-maven/**/*.jar
```

#### 12.6.5 Maven `-pl '!module'` syntax is rejected by Maven 3.9 + `-am`

`mvn -pl '!rag-test,rag-app' -am package` fails with "Could not find the
selected project in the reactor: rag-test". The negation form is
officially supported since Maven 3.6 but the parser trips on the comma
in the same arg when combined with `-am`.

**Fix** — at build time, copy the parent pom to `pom.xml.original`,
`sed -i '/<module>rag-test<\/module>/d' pom.xml`, run mvn with plain
`-am`, then restore the original. Ugly but reliable.

```dockerfile
RUN cp pom.xml pom.xml.original && \
    sed -i '/<module>rag-test<\/module>/d' pom.xml && \
    mvn -pl rag-app -am -B -DskipTests package && \
    cp pom.xml.original pom.xml && \
    rm pom.xml.original
```

#### 12.6.6 `depends_on.condition: service_healthy` breaks podman-compose dep walker

When the dependency service is gated by a `profiles:` list,
`podman-compose up -d app` raises `KeyError: 'redis'` from the dep
walker. Docker compose handles this correctly; podman-compose 1.6.0
doesn't.

**Fix** — for the legacy "manually-started `rag-redis-stack`" workflow,
drop `depends_on` entirely. The app's own healthcheck + Spring retry
covers transient drops. Document this trade-off in the YAML comment.

### 12.7 Cluster 4: `RedisProperties` reads from `spring.rag.redis.*` but `application.yml` populates `spring.data.redis.*`

`RedisProperties.host` defaults to `"127.0.0.1"`. The `REDIS_HOST` env
var doesn't reach it. To wire from env you need either:
1. Add `spring.rag.redis.host: ${REDIS_HOST:localhost}` to `application.yml`
2. Use `SPRING_APPLICATION_JSON` to inject the values as a first-class
   PropertySource (the pattern we use for `rag.siliconflow.*`).

The compose file does (2) so the `app` service can point at
`REDIS_HOST=host.docker.internal` without code changes. If you want
(1) instead, edit `application.yml` and drop the
`SPRING_APPLICATION_JSON` block.

---

## 13. Phase 7 Cluster 5 lessons — MDC stage instrumentation

Cluster 5 wired `stage` + `queryHash` + `jobId` MDC keys through every
step of QAServiceImpl + IngestServiceImpl and produced a `logback-spring.xml`
that renders them in the log line. Five patterns hit that should have
been obvious in hindsight:

### 13.1 MDC is thread-local — async executors lose the HTTP-thread context

This was the single most useful find of the cluster. The `IngestServiceImpl`
async path submits work to a daemon `ExecutorService`. The worker thread
**does not inherit the HTTP-thread MDC** — `tenant` and `requestId`
become empty on every async log line, so async errors are practically
ungreppable.

**Fix** — snapshot the HTTP-thread MDC just before `asyncExecutor.submit()`,
re-install it inside the `Runnable` body, and `MDC.clear()` in the
finally block (so the next job on this thread doesn't see the previous
job's keys):

```java
Map<String, String> submittedContext = PipelineMdc.snapshot();
asyncExecutor.submit(() -> {
    PipelineMdc.restore(submittedContext);
    try {
        runPipeline(document, job);
    } finally {
        MDC.clear();  // ← not "remove" — clear EVERYTHING
    }
});
```

**The gotcha**: at the HTTP boundary the filter sets `tenant` + `requestId`.
Between `submit()` and the worker running, those keys are gone. The worker
runs with an empty MDC unless you re-install. **Without this, the entire
`%X{tenant}` rendering in your logback pattern is wrong for async work.**

### 13.2 One outer `try/finally` is safer than `MDC.remove` inside every `try`

The QAServiceImpl `answer()` method has 8 stage `try/finally` blocks
plus early-return paths (cache HIT, empty retrieval, exception in LLM).
Trying to keep the MDC clean by putting `MDC.remove(stage)` at the
bottom of each `finally` looks right but is fragile — a future refactor
that adds a 9th stage will forget the remove, and MDC leaks across
requests on the same thread.

**Fix** — split the method into two:

```java
public Answer answer(Query query) {
    PipelineMdc.put(KEY_QUERY_HASH, hashQuery(query.rawText()));
    try {
        return answerInternal(query, ...);  // 8-stage chain lives here
    } finally {
        MDC.remove(KEY_QUERY_HASH);
        MDC.remove(KEY_STAGE);  // belt + suspenders
    }
}
```

The outer method owns the per-request MDC; the inner method owns the
per-stage MDC. There's exactly one place to add a new request-scoped
key (the outer method) and exactly one place to add a new stage-scoped
key (each inner `try`).

### 13.3 `lenient()` for stubs used only on some branches of a Mockito test

Mockito's strict-stubs default (`@ExtendWith(MockitoExtension.class)`)
fails the test if you stub a method that the SUT didn't call. With
publish + retry + embedding-fallback branches in a single test, that's
painful: you stub `embedBatch`, `dimension`, `upsert` — but if the
test happens to take the embed-failure path, only `embedBatch` gets
called, and Mockito screams about the other two.

**Fix** — wrap conditional stubs with `lenient()`:

```java
@BeforeEach void setUp() {
    // Strict stubs here would fail the test when the embed-failure
    // path doesn't exercise dimension()/upsert().
    lenient().when(embeddingGateway.dimension()).thenReturn(DIM);
    lenient().when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
    lenient().when(vectorStore.upsert(any())).thenReturn(n);
}
```

The alternative — moving the stubs into the test bodies that exercise
each branch — is cleaner in theory but quadruples the code in tests
that already have 6 cases.

### 13.4 Default-value trick: `%X{stage:-}` keeps the field present even when empty

```xml
<property name="LOG_PATTERN_CONSOLE"
          value="%d{HH:mm:ss} %5p [%X{tenant:-none}] [%X{requestId:-none}] [%X{jobId:-}] [%X{queryHash:-}] [stage=%X{stage:-}] %logger : %m%n%xException"/>
```

`%X{stage:-}` renders the MDC value, OR the literal string `-` if
absent. Without the default, log lines OUTSIDE a request (boot, scheduler,
healthcheck) render as `[stage=]`. **Same shape as in-request lines —
makes regex `\[stage=(\w+)\]` work uniformly.** Without the default,
you'd have `\[stage=([\w]+|)\]` or `stage=\s*` patterns that break
constantly.

### 13.5 Logback `%wEx` is recognized by 1.5.x but not in the bundled Spring Boot pattern parser

Symptom — adding `%wEx` (the "wrapped exception" format that
includes class info) to the pattern crashes Spring Boot at startup
with `Logback configuration error detected: There is no conversion
class registered for conversion word [wEx]`. The pattern parser in
`LogbackLoggingSystem.loadConfiguration()` (Spring Boot 3.3.5) reports
the word as unknown even though Logback 1.5.11 lists `%wEx` in the
docs.

**Fix** — use `%xException` instead. It's always available, prints
the full stack trace with `Caused by:` chains, and Spring Boot's
parser never complains.

### 13.6 Live verification: tail the log while curling, not just compile + tests

The MDC plumbing has zero observable effect on the JVM or the HTTP
response — neither compile-time nor runtime tests can say "yes, the
log line now includes `[tenant=acme] [stage=embed]`". You have to
look at the actual log file. The cheapest verification is:

```bash
curl -H "X-Tenant-Id: acme" -H "X-Request-Id: r-1" -X POST \
     http://localhost:18081/api/qa -d '...'
sleep 1
grep '\[acme\] \[r-1\]' /tmp/rag-app.log
```

If grep returns one or more lines with `[acme]` and `[r-1]`, the MDC
is plumbed end-to-end. If it returns nothing, either the filter isn't
registered or the pattern token is misspelled (typo in `%X{tenant:-none}`
vs `%X{tennat:-none}` renders as literal "none", same as a missing key —
silent failure).

---

## Appendix: Common dotenv pitfalls

|| Anti-pattern | Why it's bad | Correct |
|---|---|---|
|| `export KEY=VALUE` | `export` is shell syntax, not dotenv. Some loaders tolerate it; others break | `KEY=VALUE` |
|| `export KEY` (bare, no value) | Sets variable to empty string, may shadow the real `KEY=VALUE` on next line | remove the line entirely |
|| `KEY = VALUE` (spaces around `=`) | Dotenv spec says no spaces. Value includes the space | `KEY=VALUE` |
|| `KEY=VALUE # comment` | Comment may be parsed as part of the value | put comments on their own line `# comment` |
|| Committing `.env` | Credential leak vector | `.env` is gitignored; commit `.env.example` with placeholder values only |

---

## 13. Phase 7 lessons — Cluster 6 (metrics + eval)

### 13.1 Gauge per-tenant dynamic tag — each call creates a new gauge

`meterRegistry.gauge("rag.qa.cache.hit.ratio", Tags.of("tenant", tenantId), obj, toValue)`
captures the `Tags.of(...)` at registration time. Each `answer()` call registers a new
gauge with a different tenant tag. This is **intentionally best-effort** — gauges are
wrapped in `try/catch(RuntimeException)` so a registration failure doesn't break the
QA chain. In production with N tenants, N gauges will accumulate. This is acceptable
for a low-cardinality label (tenant is a small set), but if tenant count grows to
hundreds, consider:
- Using a `Timer` with `record(...)` instead of `gauge()` for rate-based metrics
- Or using `FunctionTimer`/`FunctionCounter` with a `ConcurrentHashMap<tenant, LongAdder>`

### 13.2 Timer.Sample nested try/finally — existing try/catch must be inside

When adding a Timer to a method that already has a `try/catch` for exceptions
(e.g., `VectorStore.search()` catches `Exception` and throws `VectorStoreUnavailableException`),
the new Timer.Sample must wrap the **entire** method body, with the existing try/catch
nested inside:

```java
public List<Chunk> search(...) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // validation
        try {
            // existing logic
            return result;
        } catch (Exception e) {
            throw new VectorStoreUnavailableException(...);
        }
    } finally {
        sample.stop(Timer.builder("rag.redis.hnsw.search.ms")
                .tag("tenant", tenantId)
                .register(meterRegistry));
    }
}
```

The `finally` must be outermost so the timer records even when the inner try/catch
re-throws a different exception.

### 13.3 Micrometer dependency missing in rag-redis

`rag-redis` module didn't have `micrometer-core` in its pom.xml. The Timer usage
in `RedisVectorStore` required it. Fix: add the dependency explicitly:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

The parent pom (spring-boot-starter-parent) manages the version via dependency management,
but `rag-redis` doesn't depend on `spring-boot-starter` directly, so the version isn't
inherited transitively through the module.

### 13.4 Eval fixture JSON schema vs Java record

`EvalFixture` is a Jackson-deserializable record. The JSON fixture schema must match
exactly:
- Field names use `@JsonProperty` annotations (e.g., `_comment`, `kbId`, `expectedChunkIds`)
- Nested records (`EvalDocument`, `EvalSection`, `EvalQuery`, `EvalExpected`)
- `expectedChunkIds` is `null` in some fixtures → must be handled as empty list in test
- `permissionTags` at the top level must be a list, not a string

### 13.5 EvalSuiteTest gating — three env vars required

`EvalSuiteTest` is gated by three `@EnabledIfEnvironmentVariable` annotations:
- `SILICONFLOW_API_KEY` — needed for real embedding (stub 16-dim doesn't match Redis HNSW 1024-dim)
- `RAG_REDIS_HOST` — points at a reachable Redis Stack
- `EVAL_SUITE` — extra opt-in so it doesn't run on every CI invocation

All three must be set (non-empty) for the test to run. If any is missing, the test
is **automatically skipped** — no false failures.

### 13.6 EvaluationService handles empty expectedChunkIds gracefully

When `expectedChunkIds` is empty or null, `recallAtK` defaults to 1.0 (no expectation
to fail). This is intentional — some fixtures only assert on `mustContainSubstring`
and `mustContainSourceUri`, not on specific chunk IDs. The `groundedRate` metric
is the primary pass/fail gate in those cases.

### 13.7 Eval report output path

`EvalSuiteTest` writes `eval-report.json` to:
1. `eval.output.dir` system property (set by the `eval` Maven profile)
2. Fallback to `EVAL_OUTPUT_DIR` env var
3. Fallback to `docs/eval`

The `eval` profile in `pom.xml` sets `eval.output.dir` to `${project.basedir}/../docs/eval`,
which resolves to the project root's `docs/eval/` directory.

---

## 14. Phase 7 lessons — Cluster 6C (Resilience4j: circuit breakers + rate limiter)

### 14.1 Function-style API beats AOP annotation when call sites aren't Spring proxies

Both `SiliconFlowEmbeddingGateway` and `RedisVectorStore` construct their collaborators with
plain `new` calls (`@Bean` methods in `SiliconFlowAutoConfiguration` / `RedisAutoConfiguration`),
not via `@Component`. That makes `@CircuitBreaker` / `@Retry` annotations on the class
**invisible to the Resilience4j AOP weaver** — the proxy is only applied to beans created by
Spring's component scan, not to `new`-ed instances returned from `@Bean` methods.

Two fixes are possible:
1. Add `@Component` (or `@Service`) to the adapter classes and let Spring proxy them.
2. Use the function-style API: `CircuitBreaker.decorateSupplier(breaker, () -> ...).get()`.

We picked (2) for two reasons — minimal blast radius (no constructor signature changes for
existing call sites, no risk of double-proxying when something else wires the adapter), and
explicit visibility at the call site ("yes, this call is breaker-guarded"). The trade-off is
that every protected call needs a `try { guarded.get() } catch (CallNotPermittedException ex)`
block to translate the breaker's signal into our typed exception — but that block is local
and easy to read.

### 14.2 Don't stack Resilience4j @Retry on top of WebClient retryWhen

The `SiliconFlowEmbeddingGateway` already had `Retry.backoff(maxRetries, ...)` inside the
`WebClient` chain (added in Phase 5-P4). Stacking a Resilience4j `@Retry` on top would
have produced **double-retry during outages** — the inner `retryWhen` would re-attempt the
upstream call, fail again, the outer `@Retry` would also re-attempt the whole block, fail
again, and we'd have made `retries²` calls instead of `retries`. The breaker still helps
because it counts failures across the entire protected call, but the explicit Resilience4j
`@Retry` would have been redundant. Lesson: when adding resilience patterns, first audit
what the underlying client library already does.

### 14.3 Inner `catch (Exception e)` re-wraps the breaker signal

First attempt at wiring the Redis breaker ended up swallowing the breaker's "circuit
breaker OPEN" message under a generic "search failed for tenant=..." wrapper:

```java
try {
    result = guarded.get();              // CallNotPermittedException here
} catch (CallNotPermittedException ex) {
    throw new VectorStoreUnavailableException(
        "Redis circuit breaker OPEN — ...", ex);
}                                        // ← outer catch (Exception e) below catches
                                         //   the new VectorStoreUnavailableException and
                                         //   re-wraps it with "search failed for ..."
```

Java's catch ordering matches on static type, not on the original exception, so a
`VectorStoreUnavailableException` thrown from the inner catch block **is** caught by the
outer `catch (Exception e)` and re-wrapped. The fix is a typed re-throw BEFORE the generic
catch:

```java
} catch (VectorStoreUnavailableException ex) {
    throw ex;                           // preserve breaker message verbatim
} catch (Exception e) {
    throw new VectorStoreUnavailableException("search failed for ...", e);
}
```

Discovered while writing the `RedisVectorStoreCircuitBreakerTest` — the test failed
because the assertion `"should mention 'circuit breaker OPEN'"` got `"search failed for..."`.
Unit-testing the failure path exposed the bug; testing only the happy path would have shipped
it.

### 14.4 E2E that uses `@MockBean RedisConnection` + `@BeforeEach` stubbing

The natural first attempt was `@DynamicPropertySource` pointing at a dead Redis host. It
failed with `RedisUnavailableException: Failed to ping Redis at 127.0.0.1:1` — the
`@PostConstruct` ping in `RedisConnection.init()` runs before any breaker wiring, so the
context fails to load and the test never gets a chance to run.

The pattern that works:
- `@MockBean RedisConnection redisConnection` — replaces the bean's `@PostConstruct` ping
  with a no-op (Mockito's default).
- `@BeforeEach` — stub `redisConnection.client()` to return a `JedisPooled` mock whose
  `ftSearch(...)` throws `JedisException`.

That boots the full Spring context, the wiring is exercised end-to-end (auto-config →
`@Bean` factory → constructor injection → Resilience4j starter picks up `application.yml`
→ `CircuitBreakerRegistry` is wired → `RedisVectorStore` looks up the `redis` breaker on
construction). The test fails fast (no Docker, no live Redis) and proves the breaker does
trip under sustained failures.

### 14.5 `mvn -pl rag-app test` won't pick up the fix until `rag-redis` is installed

The first run of `Resilience4jEndToEndIT` failed with `search failed for tenant=...` even
though the source code clearly had the breaker fix. Root cause: `rag-app` depends on
`rag-redis` as a multi-module dependency, and `mvn -pl rag-app test` reuses the
**already-installed jar in `~/.m2`** for the upstream modules (no `--also-make`).

The fix is `mvn -pl rag-redis -am install -DskipTests` first, then `mvn -pl rag-app test`.
Or run `mvn install` at the root. Either way the lesson: **after editing an upstream
module, re-install before running a downstream test**. The full `mvn verify` from the root
already does this automatically — it's only the selective `-pl` runs that surprise you.

### 14.6 `recordExceptions` is matched on the thrown type, not the cause

Resilience4j's `recordExceptions` config is checked against the exception that the
**guarded call** throws, not against any nested cause. So when `RedisVectorStore.search`
catches `JedisException` and re-throws `VectorStoreUnavailableException`, only
`VectorStoreUnavailableException` (not `JedisException`) needs to be in
`recordExceptions`. If you forget and only list `JedisException`, the breaker will
under-count failures during outages — it sees a "non-recorded" exception, treats it as
a success, and never trips.

### 14.7 Rate limiter placement matters: gate the entry point, not the deep callee

We considered three places for the Q&A rate limiter:
1. `RagController.qa(...)` — but that ties the rate limit to the HTTP transport.
2. `RedisVectorStore.search(...)` — but per-call limits there double-count (one QA
   request fans out to several searches if the rewrite path includes multi-query).
3. `QAServiceImpl.answer(...)` — one entry per user-facing request, regardless of how
   many downstream calls happen. ✅

Rate-limit at the **logical entry point of the operation**, not at the technical
chokepoint. The latter either under-limits (misses cached / fast paths) or over-limits
(starves the operator of legitimate fan-out).

---

## Appendix: Eval toolchain quick reference

| Command | Purpose |
|---|---|
| `mvn test -Peval -pl rag-test` | Run EvalSuiteTest (requires env vars) |
| `SILICONFLOW_API_KEY=xxx RAG_REDIS_HOST=localhost EVAL_SUITE=1 mvn test -Peval -pl rag-test` | Run with real SiliconFlow + Redis |
| `cat docs/eval/eval-report.json` | View last run results |