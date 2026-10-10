#!/usr/bin/env bash
# Crash-recovery check: fire a burst of keyed requests at ONE dispatch pod, force-kill that pod
# mid-burst, then confirm the stale-trip sweep leaves no REQUESTED row older than the threshold.
# Also confirms trips survive a MySQL pod restart (PVC). Exits non-zero on any violation.
set -euo pipefail

NAMESPACE=atlas
BURST="${1:-150}"
# Seconds into the burst at which the pod is force-killed. Several delays are tried because the
# system is fast enough that a late kill can land after every request has finished.
KILL_DELAYS="${KILL_DELAYS:-0.3 0.15 0.05 0.5}"
STALE_SECONDS=60
OUT="$(mktemp -d)"
trap 'kill $PF_PID 2>/dev/null || true; rm -rf "$OUT"' EXIT

PASSWORD="$(kubectl -n "$NAMESPACE" get secret mysql-credentials -o jsonpath='{.data.root-password}' | base64 -d)"
sql() { kubectl -n "$NAMESPACE" exec deploy/mysql -- mysql -uroot -p"$PASSWORD" -N -e "$1" 2>/dev/null; }

BODY='{"riderId":"R-001","pickup":{"lat":37.77,"lng":-122.42},"drop":{"lat":37.80,"lng":-122.41}}'
STUCK=0
for KILL_DELAY in $KILL_DELAYS; do
  TAG="burst-$(date +%s)"
  kubectl -n "$NAMESPACE" rollout status deploy/dispatch-service --timeout=180s >/dev/null
  POD="$(kubectl -n "$NAMESPACE" get pods -l app=dispatch-service --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')"
  kubectl -n "$NAMESPACE" port-forward "pod/$POD" 18081:8080 >/dev/null 2>&1 &
  PF_PID=$!
  sleep 3
  rm -f "$OUT"/*.code
  # The kill is on its own timer, started with the burst, so it lands while requests are in flight
  # no matter how long it takes to spawn the curls.
  ( sleep "$KILL_DELAY"; kubectl -n "$NAMESPACE" delete pod "$POD" --grace-period=0 --force >/dev/null 2>&1 ) &
  KILL_PID=$!
  PIDS=()
  for i in $(seq 1 "$BURST"); do
    curl -s -m 20 -o /dev/null -w "%{http_code}\n" -X POST localhost:18081/rides \
        -H 'Content-Type: application/json' -H "Idempotency-Key: $TAG-$i" -d "$BODY" > "$OUT/$i.code" &
    PIDS+=($!)
  done
  wait "${PIDS[@]}" "$KILL_PID"  # not a bare wait: the background port-forward never exits
  kill $PF_PID 2>/dev/null || true
  echo "attempt kill-delay=${KILL_DELAY}s, client results (000 = connection died with the pod):"; cat "$OUT"/*.code | sort | uniq -c
  sleep 2
  STUCK="$(sql "select count(*) from atlas_trip.trips where idempotency_key like '$TAG-%' and status='REQUESTED'")"
  echo "  trips by status right after the kill: $(sql "select status,count(*) from atlas_trip.trips where idempotency_key like '$TAG-%' group by status" | tr '\t\n' ' =,')"
  echo "  REQUESTED rows left by the kill: $STUCK"
  [ "$STUCK" -gt 0 ] && break
done
# The test only means something if the kill interrupted requests and left rows behind.
[ "$STUCK" -gt 0 ] || { echo "INCONCLUSIVE: no attempt left a stuck row (tried delays: $KILL_DELAYS)"; exit 2; }

# Sweep: wait for every REQUESTED row of the last attempt to resolve (threshold + one sweep interval + margin).
for i in $(seq 1 24); do
  LEFT="$(sql "select count(*) from atlas_trip.trips where idempotency_key like '$TAG-%' and status='REQUESTED'")"
  echo "poll $i: REQUESTED left = $LEFT"
  [ "$LEFT" = "0" ] && break
  sleep 10
done
echo "trips by status after the sweep: $(sql "select status,count(*) from atlas_trip.trips where idempotency_key like '$TAG-%' group by status" | tr '\t\n' ' =,')"
STALE="$(sql "select count(*) from atlas_trip.trips where status='REQUESTED' and claimed_at < now() - interval $STALE_SECONDS second")"
echo "REQUESTED rows older than ${STALE_SECONDS}s (whole table): $STALE"
[ "$LEFT" = "0" ] && [ "$STALE" = "0" ] || { echo "FAIL: stuck REQUESTED rows remain"; exit 1; }

# PVC: row count must survive deleting the MySQL pod.
BEFORE="$(sql "select count(*) from atlas_trip.trips")"
kubectl -n "$NAMESPACE" delete pod -l app=mysql --wait=true >/dev/null
kubectl -n "$NAMESPACE" rollout status deploy/mysql --timeout=180s >/dev/null
sleep 20
AFTER="$(sql "select count(*) from atlas_trip.trips")"
echo "trips before/after MySQL pod restart: $BEFORE / $AFTER"
[ "$BEFORE" = "$AFTER" ] || { echo "FAIL: rows lost across MySQL restart"; exit 1; }
echo "PASS"
