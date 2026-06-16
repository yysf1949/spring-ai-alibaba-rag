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
docker exec rag-redis-stack redis-cli KEYS "rag:*"
docker exec rag-redis-stack redis-cli FT.SEARCH "rag:active:tenant-A:kb-prod-001" \
  "@tenantId:{tenant\\-A} @kbId:{kb\\-prod\\-001} @status:{ACTIVE}" LIMIT 0 3
docker exec rag-redis-stack redis-cli GET "rag:publish:tenant-A:kb-prod-001"
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

## Appendix: Common dotenv pitfalls

| Anti-pattern | Why it's bad | Correct |
|---|---|---|
| `export KEY=VALUE` | `export` is shell syntax, not dotenv. Some loaders tolerate it; others break | `KEY=VALUE` |
| `export KEY` (bare, no value) | Sets variable to empty string, may shadow the real `KEY=VALUE` on next line | remove the line entirely |
| `KEY = VALUE` (spaces around `=`) | Dotenv spec says no spaces. Value includes the space | `KEY=VALUE` |
| `KEY=VALUE # comment` | Comment may be parsed as part of the value | put comments on their own line `# comment` |
| Committing `.env` | Credential leak vector | `.env` is gitignored; commit `.env.example` with placeholder values only |