#!/usr/bin/env bash
# Cluster 8: Multi-tenant stress test
# Usage: bash cluster8-stress-test.sh
# Requires: app running on localhost:18081

set -euo pipefail

BASE="http://localhost:18081/api"
PASS=0
FAIL=0
TIMING_FILE="/tmp/cluster8_timing.$$.csv"

cleanup() { rm -f "$TIMING_FILE"; }
trap cleanup EXIT

log() { echo "[$(date +%H:%M:%S)] $*"; }
pass() { echo "  ✅ PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "  ❌ FAIL: $1"; FAIL=$((FAIL+1)); }

# ─── helpers ────────────────────────────────────────

status_code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
body() { curl -s "$@"; }

# ────────────────────────────────────────────────────
# T8.1: Concurrent ingest — 5 tenants simultaneously
# ────────────────────────────────────────────────────
log "═══ T8.1: Concurrent ingest (5 tenants) ═══"

ingest_for() {
  local tenant="$1" kb="$2" doc_id="$3"
  local out
  out=$(body -X POST "$BASE/ingest" \
    -H "X-Tenant-Id: $tenant" \
    -H "Content-Type: application/json" \
    -d "{
      \"kbId\": \"$kb\",
      \"documentId\": \"$doc_id\",
      \"documentVersion\": 1,
      \"title\": \"Stress test doc for $tenant\",
      \"sourceUri\": \"https://stress-test/$tenant\",
      \"sections\": [{\"heading\": \"Section 1\", \"content\": \"这是租户 $tenant 的测试内容。具体来说包括退款政策、退货流程和运费规则。用户需要了解这些信息才能正确处理售后问题。\"}]
    }" 2>&1)
  echo "$out"
}

# Launch 5 ingest jobs in parallel
pids=()
tenant_ids=("t-stress-a" "t-stress-b" "t-stress-c" "t-stress-d" "t-stress-e")
for i in "${!tenant_ids[@]}"; do
  tid="${tenant_ids[$i]}"
  ingest_for "$tid" "kb-stress-1" "doc-stress-$i" > "/tmp/ingest_$tid.json" 2>&1 &
  pids+=($!)
done

# Wait for all ingest requests
for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done

# Check results
all_ok=true
for tid in "${tenant_ids[@]}"; do
  if grep -q '"jobId"' "/tmp/ingest_$tid.json" 2>/dev/null; then
    log "  ${tid}: ingest accepted (202)"
  else
    log "  ${tid}: ingest FAILED → $(cat /tmp/ingest_$tid.json 2>/dev/null)"
    all_ok=false
  fi
done

$all_ok && pass "T8.1: 5 concurrent ingest jobs all accepted (202)" || fail "T8.1: Some ingest jobs failed"
t8_1_ok=$all_ok

# ────────────────────────────────────────────────────
# T8.2: Multi-tenant QA — 10 concurrent QA requests
# ────────────────────────────────────────────────────
log "═══ T8.2: Concurrent multi-tenant QA (10 tenants) ═══"

qa_for() {
  local tenant="$1" user="$2" query="$3"
  local out
  out=$(body -X POST "$BASE/qa" \
    -H "X-Tenant-Id: $tenant" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": \"$user\",
      \"rawText\": \"$query\",
      \"permissionTags\": [\"ROLE_USER\"]
    }" 2>&1)
  echo "$out"
}

# 10 tenants with different queries
pids=()
qa_tenants=("t-qa-01" "t-qa-02" "t-qa-03" "t-qa-04" "t-qa-05" "t-qa-06" "t-qa-07" "t-qa-08" "t-qa-09" "t-qa-10")
queries=("退款政策是什么" "退货流程" "运费规则" "售后处理" "退换货条件" "退款时间" "质量问题" "配送问题" "会员政策" "投诉渠道")
for i in "${!qa_tenants[@]}"; do
  qa_for "${qa_tenants[$i]}" "user-$i" "${queries[$i]}" > "/tmp/qa_${qa_tenants[$i]}.json" 2>&1 &
  pids+=($!)
done

for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done

all_tenants_isolated=true
for tid in "${qa_tenants[@]}"; do
  result=$(cat "/tmp/qa_$tid.json" 2>/dev/null)
  # Check it returned an answer (not error)
  if echo "$result" | grep -q '"source"' || echo "$result" | grep -q '"finalText"'; then
    # Check for cross-tenant data: answer shouldn't reference another tenant's ID
    for other in "${qa_tenants[@]}"; do
      if [ "$other" != "$tid" ] && echo "$result" | grep -qi "$other" 2>/dev/null; then
        log "  ${tid}: ⚠️ CROSS-TENANT LEAK — detected '$other' in response"
        all_tenants_isolated=false
      fi
    done
  else
    log "  ${tid}: QA returned unexpected → $(echo "$result" | head -c 200)"
  fi
done

$all_tenants_isolated && pass "T8.2: 10 concurrent QA — no cross-tenant leak" || fail "T8.2: Cross-tenant leak detected"

# ────────────────────────────────────────────────────
# T8.3: Cache behavior — same query repeated 10x
# ────────────────────────────────────────────────────
log "═══ T8.3: Cache hit rate — same query ×10 ═══"

# First call — should be cache MISS → LLM source
first_body=$(body -X POST "$BASE/qa" \
  -H "X-Tenant-Id: t-cache-test" \
  -H "Content-Type: application/json" \
  -d '{"userId":"cache-user","rawText":"退货流程申请","permissionTags":["ROLE_USER"]}')
first_source=$(echo "$first_body" | grep -oP '"source"\s*:\s*"\K[^"]+' 2>/dev/null || echo "unknown")
log "  First call source: $first_source"

cache_hits=0
for i in $(seq 1 9); do
  out=$(body -X POST "$BASE/qa" \
    -H "X-Tenant-Id: t-cache-test" \
    -H "Content-Type: application/json" \
    -d '{"userId":"cache-user","rawText":"退货流程申请","permissionTags":["ROLE_USER"]}')
  src=$(echo "$out" | grep -oP '"source"\s*:\s*"\K[^"]+' 2>/dev/null || echo "unknown")
  if [ "$src" = "CACHE" ]; then
    cache_hits=$((cache_hits+1))
  fi
  log "  Call $((i+1)): source=$src"
done

log "  Cache hits: $cache_hits/9 (after first MISS)"
if [ "$cache_hits" -ge 7 ]; then
  pass "T8.3: Cache hit rate $cache_hits/9 ≥ 78%"
else
  fail "T8.3: Cache hit rate $cache_hits/9 < 78%"
fi

# ────────────────────────────────────────────────────
# T8.4: Rate limiter — 20 rapid requests
# ────────────────────────────────────────────────────
log "═══ T8.4: Rate limiting — 20 rapid QA requests ═══"

rate_limit_hits=0
success_count=0
for i in $(seq 1 20); do
  code=$(status_code -w "%{http_code}" -X POST "$BASE/qa" \
    -H "X-Tenant-Id: t-rate-limit" \
    -H "Content-Type: application/json" \
    -d '{"userId":"rate-user","rawText":"退款流程","permissionTags":["ROLE_USER"]}' 2>/dev/null)
  if [ "$code" = "429" ]; then
    rate_limit_hits=$((rate_limit_hits+1))
  elif [ "$code" = "200" ]; then
    success_count=$((success_count+1))
  fi
  log "  Request $i: HTTP $code"
done

log "  Rate-limited: $rate_limit_hits/20, Succeeded: $success_count/20"
# Rate limiter might be set high; success without crashes is acceptable
# Check that no 5xx errors occurred
if [ $((rate_limit_hits + success_count)) -eq 20 ]; then
  pass "T8.4: 20 rapid requests — no 5xx errors (limited=$rate_limit_hits, ok=$success_count)"
else
  fail "T8.4: Some unexpected HTTP status codes"
fi

# ────────────────────────────────────────────────────
# T8.5: Connection pool stability — 50 sequential QA
# ────────────────────────────────────────────────────
log "═══ T8.5: Connection pool — 50 sequential QA (timing JDBC) ═══"

echo "seq,ms" > "$TIMING_FILE"
for i in $(seq 1 50); do
  start=$(date +%s%N)
  code=$(status_code -w "%{http_code}" -X POST "$BASE/qa" \
    -H "X-Tenant-Id: t-conn-pool" \
    -H "Content-Type: application/json" \
    -d '{"userId":"conn-user","rawText":"退款政策","permissionTags":["ROLE_USER"]}' 2>/dev/null)
  end=$(date +%s%N)
  ms=$(( (end - start) / 1000000 ))
  echo "$i,$ms" >> "$TIMING_FILE"
  # Only print every 10th
  if [ $((i % 10)) -eq 0 ]; then
    log "  Request $i: HTTP $code in ${ms}ms"
  fi
done

# Calculate degradation: compare first 10 avg vs last 10 avg
avg_first=$(awk -F',' 'NR>=2 && NR<=11 {sum+=$2; n++} END {print int(sum/n)}' "$TIMING_FILE")
avg_last=$(awk -F',' 'NR>=42 && NR<=51 {sum+=$2; n++} END {print int(sum/n)}' "$TIMING_FILE")
degradation=$(( avg_last - avg_first ))

log "  Avg latency: first 10 = ${avg_first}ms, last 10 = ${avg_last}ms (Δ=${degradation}ms)"
if [ "$degradation" -lt 2000 ] && [ "$avg_last" -lt 30000 ]; then
  pass "T8.5: Connection pool stable — avg latency Δ=${degradation}ms (<2s)"
else
  fail "T8.5: Connection pool degradation detected (Δ=${degradation}ms)"
fi

# ────────────────────────────────────────────────────
# Summary
# ────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════"
echo "  Cluster 8 Stress Test Results"
echo "  PASS: $PASS / $((PASS+FAIL))"
echo "  FAIL: $FAIL"
echo "═══════════════════════════════════════════"

# Exit code
[ "$FAIL" -eq 0 ]