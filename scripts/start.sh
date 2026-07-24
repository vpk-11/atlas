#!/usr/bin/env bash
# Full local setup for Atlas: build -> containerize -> deploy to Kind -> verify.
# This is the same shape a CI/CD pipeline would automate (root CLAUDE.md
# deliberately has no automated CI/CD for v1) - run manually, step by step,
# fail loud on any step.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME=atlas
NAMESPACE=atlas
SERVICES=(dispatch-service pricing-service trip-service)

log() { echo; echo "==> $*"; }

log "Checking required tools..."
for tool in docker kind kubectl mvn; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool"; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker daemon not reachable (is colima running? try: colima start)"; exit 1; }

# --- 1. Seed data ---------------------------------------------------------
# No external seed step: dispatch-service seeds D-001..D-999 in-app on every
# startup (see dispatch-service/CLAUDE.md). Keeping seeding in-app, not a
# separate seed-data/ directory, avoids two sources of truth for the same
# driver records.
log "Seed data: in-app seeder in dispatch-service, nothing to run here."

# --- 2. Build jars ---------------------------------------------------------
for svc in "${SERVICES[@]}"; do
  log "Building $svc..."
  (cd "$ROOT_DIR/$svc" && ./mvnw -q -B -DskipTests package)
done

# --- 3. Build docker images -------------------------------------------------
for svc in "${SERVICES[@]}"; do
  log "Building image atlas/$svc:local..."
  docker build -t "atlas/$svc:local" "$ROOT_DIR/$svc"
done

# --- 4. Kind cluster ---------------------------------------------------------
if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  log "Creating kind cluster '$CLUSTER_NAME'..."
  kind create cluster --config "$ROOT_DIR/k8s/kind-config.yaml"
else
  log "Kind cluster '$CLUSTER_NAME' already exists, reusing."
fi
kubectl config use-context "kind-$CLUSTER_NAME"

log "Loading images into kind..."
for svc in "${SERVICES[@]}"; do
  kind load docker-image "atlas/$svc:local" --name "$CLUSTER_NAME"
done

# --- 5. Namespace + secrets -------------------------------------------------
kubectl apply -f "$ROOT_DIR/k8s/00-namespace.yaml"

log "Applying MySQL credentials secret..."
MYSQL_ROOT_PASSWORD="${ATLAS_MYSQL_ROOT_PASSWORD:-$(openssl rand -hex 16)}"
kubectl -n "$NAMESPACE" create secret generic mysql-credentials \
  --from-literal=root-password="$MYSQL_ROOT_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

log "Applying Prometheus config..."
kubectl -n "$NAMESPACE" create configmap prometheus-config \
  --from-file="$ROOT_DIR/monitoring/prometheus/prometheus.yml" \
  --dry-run=client -o yaml | kubectl apply -f -

log "Applying Grafana provisioning + dashboards..."
kubectl -n "$NAMESPACE" create configmap grafana-datasources \
  --from-file="$ROOT_DIR/monitoring/grafana/provisioning/datasources" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create configmap grafana-dashboard-provider \
  --from-file="$ROOT_DIR/monitoring/grafana/provisioning/dashboards" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create configmap grafana-dashboards \
  --from-file="$ROOT_DIR/monitoring/grafana/dashboards" \
  --dry-run=client -o yaml | kubectl apply -f -

# --- 6. Application manifests -----------------------------------------------
log "Applying MySQL, Dispatch, Pricing, Trip, Prometheus, Grafana manifests..."
kubectl apply -f "$ROOT_DIR/k8s/01-mysql.yaml"
kubectl -n "$NAMESPACE" rollout status deployment/mysql --timeout=180s

kubectl apply -f "$ROOT_DIR/k8s/03-pricing.yaml"
kubectl apply -f "$ROOT_DIR/k8s/04-trip.yaml"
kubectl -n "$NAMESPACE" rollout status deployment/pricing-service --timeout=180s
kubectl -n "$NAMESPACE" rollout status deployment/trip-service --timeout=180s

kubectl apply -f "$ROOT_DIR/k8s/02-dispatch.yaml"
kubectl -n "$NAMESPACE" rollout status deployment/dispatch-service --timeout=180s

kubectl apply -f "$ROOT_DIR/k8s/05-prometheus.yaml"
kubectl apply -f "$ROOT_DIR/k8s/06-grafana.yaml"
kubectl -n "$NAMESPACE" rollout status deployment/prometheus --timeout=120s
kubectl -n "$NAMESPACE" rollout status deployment/grafana --timeout=120s

# --- 7. Verify ---------------------------------------------------------------
log "Smoke testing POST /rides through dispatch-service..."
kubectl -n "$NAMESPACE" port-forward svc/dispatch-service 8080:8080 >/tmp/atlas-smoke-pf.log 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/rides \
  -H 'Content-Type: application/json' \
  -d '{"riderId":"R-001","pickup":{"lat":37.7749,"lng":-122.4194},"drop":{"lat":37.7849,"lng":-122.4094}}' \
  --max-time 10)

kill "$PF_PID" 2>/dev/null || true
trap - EXIT

if [ "$RESPONSE" != "200" ]; then
  echo "Smoke test FAILED: POST /rides returned HTTP $RESPONSE"
  exit 1
fi

log "Smoke test passed (HTTP 200)."
log "Atlas is up. Useful next steps:"
echo "  kubectl -n $NAMESPACE port-forward svc/dispatch-service 8080:8080   # POST /rides"
echo "  kubectl -n $NAMESPACE port-forward svc/grafana 3000:3000            # dashboards"
echo "  kubectl -n $NAMESPACE port-forward svc/prometheus 9090:9090         # raw metrics/targets"
echo "  BASE_URL=http://localhost:8080 k6 run load-testing/rides-load-test.js"
echo "  load-testing/chaos-test.sh   # kill a dispatch pod mid-load-test"
