#!/usr/bin/env bash
# Fixed-rate load runs against the local OSRM stub instead of the public OSRM demo
# API, to measure Atlas's own throughput. Usage: stub-load-test.sh [duration] [rate...]
# e.g. stub-load-test.sh 60s 150 200 250. Public OSRM stays the default: the override
# is removed on exit.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE=atlas
DURATION="${1:-60s}"
shift || true
RATES=("$@")
[ ${#RATES[@]} -gt 0 ] || RATES=(150 200 250 300 400)

trap 'kubectl -n "$NAMESPACE" set env deploy/dispatch-service OSRM_BASE_URL- >/dev/null; kubectl -n "$NAMESPACE" rollout status deploy/dispatch-service --timeout=180s' EXIT

kubectl apply -f "$ROOT_DIR/k8s/08-osrm-stub.yaml"
kubectl -n "$NAMESPACE" rollout restart deploy/osrm-stub
kubectl -n "$NAMESPACE" rollout status deploy/osrm-stub --timeout=120s
kubectl -n "$NAMESPACE" set env deploy/dispatch-service OSRM_BASE_URL=http://osrm-stub:5000
kubectl -n "$NAMESPACE" rollout status deploy/dispatch-service --timeout=180s

kubectl -n "$NAMESPACE" create configmap k6-script \
  --from-file="$ROOT_DIR/load-testing/rides-load-test.js" \
  --dry-run=client -o yaml | kubectl apply -f -

run_rate() {
  kubectl -n "$NAMESPACE" delete job k6-load-test --ignore-not-found
  kubectl set env --local -f "$ROOT_DIR/k8s/07-k6-job.yaml" RATE="$1" DURATION="$2" -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" wait --for=condition=complete job/k6-load-test --timeout=600s
  kubectl -n "$NAMESPACE" logs job/k6-load-test | grep '^RESULT'
}

# Warm-up pass, discarded: right after the dispatch rollout the JVMs and connection
# pools are cold and the first measured rate otherwise shows spurious failures.
run_rate "${RATES[0]}" 20s > /dev/null

for rate in "${RATES[@]}"; do
  run_rate "$rate" "$DURATION"
done
