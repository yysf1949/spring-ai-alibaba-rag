#!/usr/bin/env bash
# scripts/demo-refund-qa.sh — end-to-end smoke test for spec §18.
#
# What this does:
#   1. POST /api/ingest    — upload a synthetic refund-policy document
#   2. poll GET /api/ingest/{jobId} until status == READY
#   3. POST /api/ingest/{jobId}/publish — atomic staging → active switch
#   4. POST /api/qa        — ask the spec's eval question
#   5. verify answer contains "运费退还" + a citation pointing at SOURCE_URI
#
# Why a shell script (not just the JUnit RefundRuleEndToEndTest)?
#   - This is the manual smoke test the spec §3 + §18 call out — operators
#     can run it after any deploy to confirm the live app still answers
#     correctly. The JUnit version is for CI.
#   - Drives the HTTP API surface end-to-end, which the JUnit version
#     does NOT (it autowires the service beans directly).
#
# Pre-conditions:
#   - The app is running (Spring Boot, port 8080 by default, override with APP_PORT).
#   - Redis Stack is reachable from the app.
#   - SILICONFLOW_API_KEY is set (real embedding; the 16-dim stub will
#     fail RediSearch validation because the index is created at DIM=1024).
#
# Usage:
#   APP_PORT=8080 SILICONFLOW_API_KEY=*** ./scripts/demo-refund-qa.sh
#   APP_PORT=18081 ./scripts/demo-refund-qa.sh        # if running in docker compose
#
# Exit codes:
#   0  — pass (answer contains the expected phrase + citation)
#   1  — any HTTP failure or timeout
#   2  — answer does NOT contain expected phrase
#   3  — answer missing expected citation
#
# Idempotency:
#   Each run uses a fresh jobId (UUID) and a fresh kbVersion (epoch seconds),
#   so rerunning won't collide with a previous ingest job. The kbVersion
#   trick avoids the "publish pointer already at v1" symptom — see LESSONS §4.

set -euo pipefail

# ── config ──────────────────────────────────────────────────────────────
APP_HOST="${APP_HOST:-localhost}"
APP_PORT="${APP_PORT:-8080}"
BASE_URL="http://${APP_HOST}:${APP_PORT}"
TENANT="${TENANT:-tenant-refund}"
KB_ID="${KB_ID:-kb-refund}"
KB_VERSION="${KB_VERSION:-$(date +%s)}"
DOCUMENT_VERSION="${DOCUMENT_VERSION:-1}"
SOURCE_URI="${SOURCE_URI:-https://docs.example.com/refund-policy}"
MUST_CONTAIN="${MUST_CONTAIN:-运费退还}"

# Synthetic refund-policy document. Three sections, one of which contains
# the MUST_CONTAIN phrase. Inlined here (rather than reading a fixture
# file) so the script is self-contained and portable.
read -r -d '' DOC_JSON <<EOF || true
{
  "kbId": "${KB_ID}",
  "documentId": "${KB_ID}/doc-refund-v${DOCUMENT_VERSION}",
  "documentVersion": ${DOCUMENT_VERSION},
  "title": "退款规则",
  "sourceUri": "${SOURCE_URI}",
  "permissionTags": ["ROLE_USER"],
  "sections": [
    { "path": "通用条款", "content": "用户在下单后 7 日内可申请无理由退款。${MUST_CONTAIN}规则：商品签收后 7 日内可申请运费退款。运费退款金额按实际支付运费计算，特殊商品（虚拟卡、定制商品）除外。" },
    { "path": "申请流程", "content": "第 1 步：登录账户进入「我的订单」。第 2 步：选择目标订单点击「申请退款」。第 3 步：填写退款原因并提交，客服将在 24 小时内审核。审核通过后，${MUST_CONTAIN}与货款同时原路退回。" },
    { "path": "特殊商品", "content": "虚拟商品、定制商品、生鲜类商品不支持 7 天无理由退款，运费亦不退还。具体以商品详情页标注为准。" }
  ]
}
EOF

QUERY='用户付了运费但商品质量问题退款，运费退吗'

# ── helpers ─────────────────────────────────────────────────────────────
say()    { printf '\033[1;36m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
fail()   { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }
ok()     { printf '\033[1;32m[ OK]\033[0m %s\n' "$*"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1" 1
}

# jq-free JSON field extractor. Works for the flat responses our app emits.
# Usage: json_field '"jobId"' "$json"
json_field() {
  local key="$1" json="$2"
  printf '%s' "$json" \
    | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
    | head -1 \
    | sed -E "s/.*:[[:space:]]*\"([^\"]*)\".*/\1/"
}

http_post() {
  # http_post <path> <body> [extra_headers...]
  local path="$1" body="$2"; shift 2
  local headers=(-H "Content-Type: application/json" -H "X-Tenant-Id: ${TENANT}")
  for h in "$@"; do headers+=(-H "$h"); done
  curl -fsS -X POST "${BASE_URL}${path}" "${headers[@]}" -d "$body"
}

http_get() {
  local path="$1"
  curl -fsS "${BASE_URL}${path}" -H "X-Tenant-Id: ${TENANT}"
}

# ── preflight ──────────────────────────────────────────────────────────
require_cmd curl
say "app         = ${BASE_URL}"
say "tenant      = ${TENANT}"
say "kbId        = ${KB_ID}  (kbVersion=${KB_VERSION})"

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
  fail "app not reachable at ${BASE_URL} — start it first (mvn spring-boot:run or docker compose up -d app)" 1
fi
ok "app health endpoint reachable"

if [ -z "${SILICONFLOW_API_KEY:-}" ]; then
  say "⚠️  SILICONFLOW_API_KEY not set — app is in stub mode (16-dim embeddings)."
  say "    The RediSearch index is configured for 1024-dim, so the FT.SEARCH upsert"
  say "    will likely fail. Set SILICONFLOW_API_KEY and restart the app for a real run."
fi

# ── 1. ingest ──────────────────────────────────────────────────────────
say "step 1/4  POST /api/ingest — upload refund-policy document"
INGEST_RESP="$(http_post /api/ingest "$DOC_JSON")" || fail "ingest POST failed" 1
JOB_ID="$(json_field 'jobId' "$INGEST_RESP")"
[ -n "$JOB_ID" ] || fail "could not parse jobId from ingest response: $INGEST_RESP" 1
ok "jobId = ${JOB_ID}"

# ── 2. poll job until READY ────────────────────────────────────────────
say "step 2/4  poll GET /api/ingest/${JOB_ID} (max 60s)"
for i in $(seq 1 60); do
  JOB_RESP="$(http_get "/api/ingest/${JOB_ID}")" || fail "ingest GET failed at attempt $i" 1
  STATUS="$(json_field 'status' "$JOB_RESP")"
  case "$STATUS" in
    READY)
      ok "ingest completed after ${i}s"
      break
      ;;
    FAILED)
      fail "ingest job FAILED — check app logs (IngestServiceImpl + RedisConnector)" 1
      ;;
    PUBLISHED)
      ok "ingest already PUBLISHED (rerun case)"
      break
      ;;
    PENDING|PROCESSING)
      if [ "$i" = "60" ]; then
        fail "ingest still ${STATUS} after 60s — check app logs" 1
      fi
      sleep 1
      ;;
    *)
      fail "unexpected job status: ${STATUS} (response: $JOB_RESP)" 1
      ;;
  esac
done

# ── 3. publish ─────────────────────────────────────────────────────────
say "step 3/4  POST /api/ingest/${JOB_ID}/publish — atomic staging → active"
PUBLISH_RESP="$(http_post "/api/ingest/${JOB_ID}/publish" '{}')" || fail "publish POST failed" 1
PSTATUS="$(json_field 'status' "$PUBLISH_RESP")"
[ "$PSTATUS" = "PUBLISHED" ] || fail "expected PUBLISHED, got: $PSTATUS ($PUBLISH_RESP)" 1
ok "kb ${KB_ID} v${KB_VERSION} is now PUBLISHED"

# ── 4. ask the spec §18 eval question ──────────────────────────────────
say "step 4/4  POST /api/qa — ask the spec §18 question"
QA_BODY=$(cat <<EOF
{
  "userId": "alice",
  "sessionId": "session-demo-1",
  "rawText": "${QUERY}",
  "kbVersion": {
    "kbId": "${KB_ID}",
    "version": ${KB_VERSION}
  },
  "permissionTags": ["ROLE_USER"]
}
EOF
)
QA_RESP="$(http_post /api/qa "$QA_BODY")" || fail "qa POST failed" 1
ANSWER_TEXT="$(json_field 'finalText' "$QA_RESP")"
ANSWER_SOURCE="$(json_field 'source' "$QA_RESP")"

say "answer.source     = ${ANSWER_SOURCE}"
say "answer.finalText  = ${ANSWER_TEXT}"

# ── assertions ─────────────────────────────────────────────────────────
# Spec §18 expects the answer to address "运费退还" — i.e. shipping fee is
# refundable. The exact surface form varies ("运费退还", "运费是可以退还的",
# "运费将原路退回"). Assert both "运费" and "退" are present AND that the
# answer is non-empty, rather than a brittle substring match on "运费退还".
if [ -z "$ANSWER_TEXT" ] || [ "$ANSWER_SOURCE" = "FALLBACK_RULE_EMPTY" ]; then
  fail "answer is empty (source=${ANSWER_SOURCE})" 2
fi
if ! printf '%s' "$ANSWER_TEXT" | grep -qF "运费" || \
   ! printf '%s' "$ANSWER_TEXT" | grep -qF "退"; then
  fail "answer does not address 运费退款: '${ANSWER_TEXT}'" 2
fi
ok "answer addresses 运费退款 (source=${ANSWER_SOURCE}, text='${ANSWER_TEXT}')"

# citation.sourceUri check — naive but adequate for a smoke test
if ! printf '%s' "$QA_RESP" | grep -qF "${SOURCE_URI}"; then
  fail "answer response does NOT contain expected citation sourceUri '${SOURCE_URI}'" 3
fi
ok "answer contains citation pointing at ${SOURCE_URI}"

say ""
ok "✅ spec §18 demo PASSED (kbVersion=${KB_VERSION}, jobId=${JOB_ID})"
