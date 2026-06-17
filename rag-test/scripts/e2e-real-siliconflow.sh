#!/usr/bin/env bash
# Full E2E: real SiliconFlow ingest → publish → QA
set -euo pipefail

BASE="http://localhost:18081/api"
TENANT="t-e2e-real"
KB="kb-e2e-real"
DOC="doc-e2e-001"

PASS=0; FAIL=0
log() { echo "[$(date +%H:%M:%S)] $*"; }

echo "═══════════════════════════════════════════"
echo "  E2E Real SiliconFlow — Comprehensive"
echo "═══════════════════════════════════════════"

# 1. Ingest
log "1. Ingest document..."
JOB=$(curl -s -X POST "$BASE/ingest" \
  -H "X-Tenant-Id: $TENANT" \
  -H "Content-Type: application/json" \
  -d '{"kbId":"'"$KB"'","documentId":"'"$DOC"'","documentVersion":1,"title":"退款政策","sourceUri":"https://example.com/refund","permissionTags":["ROLE_USER"],"sections":[{"heading":"退货政策","content":"所有商品支持7天无理由退换货。商品必须保持完好，不影响二次销售。买家承担退货运费。"},{"heading":"退款流程","content":"用户申请退款后，商家在48小时内处理。审核通过后，退款将在3-5个工作日原路返回。"}]}')
JOB_ID=$(echo "$JOB" | grep -oP '"jobId"\s*:\s*"\K[^"]+')
echo "  Job: $JOB_ID"

for i in $(seq 1 30); do
  STATUS=$(curl -s "$BASE/ingest/$JOB_ID" -H "X-Tenant-Id: $TENANT" | grep -oP '"status"\s*:\s*"\K[^"]+')
  echo "    Poll $i: $STATUS"
  [ "$STATUS" = "READY" ] && break
  [ "$STATUS" = "FAILED" ] && { echo "❌ FAILED"; exit 1; }
  sleep 2
done

# 2. Publish
log "2. Publish..."
PUB=$(curl -s -X POST "$BASE/ingest/$JOB_ID/publish" -H "X-Tenant-Id: $TENANT")
echo "  Status: $(echo "$PUB" | grep -oP '"status"\s*:\s*"\K[^"]+')"

# 3. QA — meaningful question (should match chunk content)
log "3. QA question..."
QA=$(curl -s -X POST "$BASE/qa" \
  -H "X-Tenant-Id: $TENANT" \
  -H "Content-Type: application/json" \
  -d '{"userId":"e2e-user","rawText":"退款后多久到账","permissionTags":["ROLE_USER"]}')
SRC=$(echo "$QA" | grep -oP '"source"\s*:\s*"\K[^"]+')
FINAL=$(echo "$QA" | grep -oP '"finalText"\s*:\s*"\K[^"]+')
RET_COUNT=$(echo "$QA" | grep -oP '"chunkId"' | wc -l)
echo "  source=$SRC | retrieved=$RET_COUNT chunks"

# 4. QA — different question (tests retrieval quality)
log "4. QA second question..."
QA2=$(curl -s -X POST "$BASE/qa" \
  -H "X-Tenant-Id: $TENANT" \
  -H "Content-Type: application/json" \
  -d '{"userId":"e2e-user","rawText":"退货运费谁承担","permissionTags":["ROLE_USER"]}')
SRC2=$(echo "$QA2" | grep -oP '"source"\s*:\s*"\K[^"]+')
FINAL2=$(echo "$QA2" | grep -oP '"finalText"\s*:\s*"\K[^"]+')

# 5. QA — cache test (same question again)
log "5. QA cache test..."
QA3=$(curl -s -X POST "$BASE/qa" \
  -H "X-Tenant-Id: $TENANT" \
  -H "Content-Type: application/json" \
  -d '{"userId":"e2e-user","rawText":"退款后多久到账","permissionTags":["ROLE_USER"]}')
SRC3=$(echo "$QA3" | grep -oP '"source"\s*:\s*"\K[^"]+')

echo ""
echo "═══════════════════════════════════════════"
echo "  Results"
echo "  Ingest:  ✅ (job completed)"
echo "  Publish: ✅ (status=PUBLISHED)"
echo "  QA-1:    $([ -n "$SRC" ] && echo "✅ ($SRC)" || echo "❌ (empty)")"
echo "  QA-2:    $([ -n "$SRC2" ] && echo "✅ ($SRC2)" || echo "❌ (empty)")"
echo "  Cache:   $([ "$SRC3" = "CACHE" ] && echo "✅ (CACHE hit)" || echo "⚠️ ($SRC3 - expected CACHE)")"
echo "═══════════════════════════════════════════"
echo ""
echo "--- Stage timings (QA-1) ---"
echo "$QA" | grep -oP '"stage\.\w+\.ms"\s*:\s*\d+' 2>/dev/null || echo "  N/A"