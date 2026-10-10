#!/usr/bin/env bash
# Runs the full verification battery against the live Kind cluster and writes one dated log under
# load-testing/results/, so quoted numbers have a committed, re-runnable source.
# Takes roughly 25 minutes. Usage: evidence.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT_DIR/load-testing/results/$(date +%F)-evidence.txt"
mkdir -p "$ROOT_DIR/load-testing/results"

section() { echo; echo "=== $*"; }
{
  echo "Atlas evidence run"
  echo "date:   $(date -u +%FT%TZ)"
  echo "commit: $(git -C "$ROOT_DIR" rev-parse --short HEAD)$(git -C "$ROOT_DIR" diff --quiet || echo ' (dirty tree)')"
  echo "nodes:  $(kubectl get nodes -o jsonpath='{.items[0].status.capacity.cpu} cpu, {.items[0].status.capacity.memory} mem')"
  echo "pods:"; kubectl -n atlas get pods --no-headers | awk '{print "  "$1, $3}'

  section "1. Concurrent same-key requests (verify-idempotency.sh 30)"
  "$ROOT_DIR/load-testing/verify-idempotency.sh" 30

  section "2. Crash then sweep, and MySQL restart (crash-sweep-test.sh 150)"
  "$ROOT_DIR/load-testing/crash-sweep-test.sh" 150

  section "3. Sustained 500 req/s on the OSRM stub, 180s, three runs after a warm-up (stub-load-test.sh)"
  "$ROOT_DIR/load-testing/stub-load-test.sh" 180s 500 500 500

  section "4. Past the ceiling: 600 req/s, 60s"
  "$ROOT_DIR/load-testing/stub-load-test.sh" 60s 600

  section "5. Chaos at ramp to 500 req/s on the stub, dispatch pod killed mid-run (USE_OSRM_STUB=1 chaos-test.sh)"
  USE_OSRM_STUB=1 "$ROOT_DIR/load-testing/chaos-test.sh" 2>&1 | grep -E "RESULT|Killing" 

  section "6. Whole-table duplicate check after everything above (check-duplicates.sh)"
  "$ROOT_DIR/load-testing/check-duplicates.sh"
} 2>&1 | tee "$LOG"
echo "wrote $LOG"
