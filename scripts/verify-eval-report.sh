#!/usr/bin/env bash
# Phase 33 T3 — Eval report gate (companion to .gitlab-ci.yml eval-gate stage).
#
# Reads the JSON report produced by EvalSuiteTest at
# docs/eval/eval-report.json and fails the CI job when any fixture
# breaches the operator-defined thresholds:
#   recall@K  >= 0.5   (per fixture — gate is on the WEAKEST, not avg)
#   groundRate >= 0.8  (per fixture — citation coverage)
#
# Usage:
#   bash scripts/verify-eval-report.sh <path/to/eval-report.json>
#
# Exit codes:
#   0  all fixtures meet thresholds
#   1  threshold breach OR missing arg / unreadable report
#
# Dependencies: bash, python3 (uses stdlib json; no jq required).

set -euo pipefail

REPORT_PATH="${1:-}"

if [[ -z "$REPORT_PATH" ]]; then
  echo "ERROR: usage: $0 <path/to/eval-report.json>" >&2
  exit 1
fi

if [[ ! -f "$REPORT_PATH" ]]; then
  echo "ERROR: eval report not found at: $REPORT_PATH" >&2
  echo "       (the eval test must run first; see -Peval in .gitlab-ci.yml)" >&2
  exit 1
fi

# Thresholds (Phase 33 T3 spec). Keep in sync with .gitlab-ci.yml header comment.
RECALL_MIN=0.5
GROUND_MIN=0.8

python3 - "$REPORT_PATH" "$RECALL_MIN" "$GROUND_MIN" <<'PY'
import json, sys

report_path, recall_min_s, ground_min_s = sys.argv[1], sys.argv[2], sys.argv[3]
recall_min = float(recall_min_s)
ground_min = float(ground_min_s)

try:
    with open(report_path, "r", encoding="utf-8") as fh:
        fixtures = json.load(fh)
except Exception as exc:
    print(f"ERROR: failed to parse {report_path}: {exc}", file=sys.stderr)
    sys.exit(1)

if not isinstance(fixtures, list):
    print(f"ERROR: {report_path} must be a JSON array of fixture objects", file=sys.stderr)
    sys.exit(1)

if not fixtures:
    print(f"ERROR: {report_path} is empty — no fixtures ran", file=sys.stderr)
    sys.exit(1)

breaches = []
rows = []
for fx in fixtures:
    name = fx.get("name", "<unnamed>")
    recall = float(fx.get("recallAtK", 0.0))
    ground = float(fx.get("groundRate", 0.0))
    citation = float(fx.get("citationCoverage", 0.0))
    passed = fx.get("pass", False)

    rows.append((name, recall, ground, citation, passed))

    if recall < recall_min:
        breaches.append(f"  - {name}: recall@K={recall:.3f} < {recall_min}")
    if ground < ground_min:
        breaches.append(f"  - {name}: groundRate={ground:.3f} < {ground_min}")
    if not passed:
        breaches.append(f"  - {name}: per-fixture pass=false")

n = len(rows)
n_pass = sum(1 for r in rows if r[4])
mean_recall = sum(r[1] for r in rows) / n
mean_ground = sum(r[2] for r in rows) / n
min_recall = min(r[1] for r in rows)
min_ground = min(r[2] for r in rows)

print(f"Eval gate report: {report_path}")
print(f"  fixtures:   {n} (per-fixture pass: {n_pass}/{n})")
print(f"  recall@K:   mean={mean_recall:.3f}  min={min_recall:.3f}  (threshold ≥ {recall_min})")
print(f"  groundRate: mean={mean_ground:.3f}  min={min_ground:.3f}  (threshold ≥ {ground_min})")
print()
print(f"  {'fixture':<24} {'recall@K':>9} {'ground':>9} {'citation':>9}  pass")
for name, recall, ground, citation, passed in rows:
    mark = "PASS" if passed else "FAIL"
    print(f"  {name:<24} {recall:>9.3f} {ground:>9.3f} {citation:>9.3f}  {mark}")
print()

if breaches:
    print(f"FAIL: {len(breaches)} threshold breach(es):", file=sys.stderr)
    for b in breaches:
        print(b, file=sys.stderr)
    sys.exit(1)

print(f"PASS: all {n} fixtures meet recall@K≥{recall_min} AND groundRate≥{ground_min}.")
sys.exit(0)
PY