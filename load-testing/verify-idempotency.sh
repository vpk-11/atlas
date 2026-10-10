#!/usr/bin/env bash
# Fires N concurrent POST /rides with ONE Idempotency-Key and checks the outcome:
# exactly one trip row, and every MATCHED response names the same trip and driver (so only
# one driver was assigned). Exits non-zero on any violation. Usage: verify-idempotency.sh [N]
set -euo pipefail

NAMESPACE=atlas
N="${1:-30}"
KEY="verify-$(date +%s)-$$"
OUT="$(mktemp -d)"
trap 'kill $PF_PID 2>/dev/null || true; rm -rf "$OUT"' EXIT

kubectl -n "$NAMESPACE" port-forward svc/dispatch-service 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
sleep 3

PASSWORD="$(kubectl -n "$NAMESPACE" get secret mysql-credentials -o jsonpath='{.data.root-password}' | base64 -d)"
sql() { kubectl -n "$NAMESPACE" exec deploy/mysql -- mysql -uroot -p"$PASSWORD" -N -e "$1" 2>/dev/null; }

BODY='{"riderId":"R-042","pickup":{"lat":37.77,"lng":-122.42},"drop":{"lat":37.80,"lng":-122.41}}'
for i in $(seq 1 "$N"); do
  ( curl -s -m 30 -o "$OUT/$i.body" -w "%{http_code}\n" -X POST localhost:18080/rides \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" > "$OUT/$i.code" ) &
done
wait

echo "status codes:"; cat "$OUT"/*.code | sort | uniq -c
ROWS="$(sql "select count(*) from atlas_trip.trips where idempotency_key='$KEY'")"
MATCHED_PAIRS="$(python3 - "$OUT" <<'PY'
import glob, json, sys
pairs = set()
for path in glob.glob(sys.argv[1] + "/*.body"):
    try:
        body = json.load(open(path))
    except ValueError:
        continue
    if body.get("status") == "MATCHED":
        pairs.add((body["tripId"], body["driverId"]))
print(len(pairs))
PY
)"
echo "rows for key: $ROWS"
echo "distinct (trip, driver) pairs among MATCHED responses: ${MATCHED_PAIRS:-0}"

[ "$ROWS" = "1" ] || { echo "FAIL: expected exactly 1 trip row"; exit 1; }
[ "${MATCHED_PAIRS:-0}" -le 1 ] || { echo "FAIL: more than one trip/driver pair returned"; exit 1; }
echo "PASS"
