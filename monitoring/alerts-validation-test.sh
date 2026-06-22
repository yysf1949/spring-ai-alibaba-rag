#!/usr/bin/env bash
# Validate prometheus-alerts.yml syntax and alert expression naming.
# Run from project root.

set -euo pipefail

ALERTS_FILE="monitoring/prometheus-alerts.yml"
EXPECTED_ALERTS=(
    "RagQaHighP95Latency"
    "RagCircuitBreakerOpen"
    "RagAnswerCacheHitRatioLow"
    "RagEmptyRetrievalHigh"
    "RagEvalRegressionCritical"
    "RagRedisMemoryHigh"
    "RagLlmHighErrorRate"
)

if [ ! -f "$ALERTS_FILE" ]; then
    echo "FAIL: $ALERTS_FILE not found"
    exit 1
fi

# YAML syntax check
if ! python3 -c "import yaml; yaml.safe_load(open('$ALERTS_FILE'))" 2>/dev/null; then
    echo "FAIL: $ALERTS_FILE is not valid YAML"
    python3 -c "import yaml; yaml.safe_load(open('$ALERTS_FILE'))"  # show error
    exit 1
fi

# Each expected alert must appear in the file
for alert in "${EXPECTED_ALERTS[@]}"; do
    if ! grep -q "alert: $alert$" "$ALERTS_FILE"; then
        echo "FAIL: missing alert: $alert"
        exit 1
    fi
done

echo "PASS: 7 alerts present and YAML valid"
