#!/usr/bin/env bash
# Chaos test centerpiece: kill a dispatch-service pod mid-load-test, prove
# client-side retry gets the request served by the surviving replica.
#
# k6 runs as an in-cluster Job against dispatch-service's ClusterIP, not from
# the host via port-forward - port-forward binds to one specific backing pod,
# so killing that pod during the test kills the whole connection instead of
# exercising the Service's actual load-balanced failover.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE=atlas

echo "==> Applying k6 script as a ConfigMap..."
kubectl -n "$NAMESPACE" create configmap k6-script \
  --from-file="$ROOT_DIR/load-testing/rides-load-test.js" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> Deleting any previous k6-load-test Job..."
kubectl -n "$NAMESPACE" delete job k6-load-test --ignore-not-found

echo "==> Starting k6 load test Job..."
kubectl apply -f "$ROOT_DIR/k8s/07-k6-job.yaml"

echo "==> Waiting for k6 pod to start..."
kubectl -n "$NAMESPACE" wait --for=condition=Ready pod -l job-name=k6-load-test --timeout=60s

echo "==> Streaming k6 logs in the background..."
kubectl -n "$NAMESPACE" logs -f job/k6-load-test &
LOGS_PID=$!

echo "==> Waiting 20s into the run before killing a dispatch pod..."
sleep 20

POD=$(kubectl -n "$NAMESPACE" get pods -l app=dispatch-service -o jsonpath='{.items[0].metadata.name}')
echo "==> Killing pod $POD mid-load-test..."
kubectl -n "$NAMESPACE" delete pod "$POD" --grace-period=0 --force

echo "==> Watching dispatch-service pods recover (15s)..."
kubectl -n "$NAMESPACE" get pods -l app=dispatch-service -w &
WATCH_PID=$!
sleep 15
kill "$WATCH_PID" 2>/dev/null || true

echo "==> Waiting for k6 Job to finish..."
kubectl -n "$NAMESPACE" wait --for=condition=complete job/k6-load-test --timeout=5m || true
wait "$LOGS_PID" 2>/dev/null || true

echo "==> Done. Check http_req_failed in the k6 summary above: it should stay"
echo "    well under 100% during the kill window if the Service failed over."
echo "    Cross-check the Grafana 'Dispatch Pod Count Over Time' panel for the dip and recovery."
