#!/usr/bin/env bash
# Runs the full verification battery against the live Kind cluster and writes one dated log under
# load-testing/results/, so quoted numbers have a committed, re-runnable source.
# Takes roughly 25 minutes. Usage: evidence.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The log must be reproducible from a commit: refuse a dirty tree (results/ itself is exempt).
if [ -n "$(git -C "$ROOT_DIR" status --porcelain -- . ':!load-testing/results')" ]; then
  echo "dirty tree: commit first so the log names the code it ran"; exit 1
fi
SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
LOG="$ROOT_DIR/load-testing/results/$(date +%F)-$SHA-evidence.txt"
mkdir -p "$ROOT_DIR/load-testing/results"

PASSWORD="$(kubectl -n atlas get secret mysql-credentials -o jsonpath='{.data.root-password}' | base64 -d)"
sql() { kubectl -n atlas exec deploy/mysql -- mysql -uroot -p"$PASSWORD" -N -e "$1" 2>/dev/null; }
TRIPS_BEFORE="$(sql 'select count(*) from atlas_trip.trips')"

section() { echo; echo "=== $*"; }
{
  echo "Atlas evidence run"
  echo "date:   $(date -u +%FT%TZ)"
  echo "commit: $SHA (clean tree)"
  echo "host:   $(uname -sm), $(sysctl -n hw.ncpu) cpu, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB; colima: $(colima list 2>/dev/null | awk 'NR==2{print $4" cpu, "$5", "$6" disk"}')"
  echo "kind:   $(kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.kubeletVersion}, {.items[0].status.capacity.cpu} cpu, {.items[0].status.capacity.memory} mem')"
  echo "osrm:   stub, STUB_LATENCY_MS=$(kubectl -n atlas get deploy osrm-stub -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="STUB_LATENCY_MS")].value}')"
  echo "image:  trip $(kubectl -n atlas get pod -l app=trip-service -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'), dispatch $(kubectl -n atlas get pod -l app=dispatch-service -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')"
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

  section "6. Duplicate check (whole table, cumulative; trips added by this run: $(( $(sql 'select count(*) from atlas_trip.trips') - TRIPS_BEFORE )))"
  "$ROOT_DIR/load-testing/check-duplicates.sh"
} 2>&1 | tee "$LOG"
echo "wrote $LOG"
