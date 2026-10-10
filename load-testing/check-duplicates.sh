#!/usr/bin/env bash
# Whole-table duplicate check: any (rider, idempotency key) with more than one trip row is a failure.
set -euo pipefail
NAMESPACE=atlas
PASSWORD="$(kubectl -n "$NAMESPACE" get secret mysql-credentials -o jsonpath='{.data.root-password}' | base64 -d)"
sql() { kubectl -n "$NAMESPACE" exec deploy/mysql -- mysql -uroot -p"$PASSWORD" -N -e "$1" 2>/dev/null; }
echo "keyed trips: $(sql "select count(*) from atlas_trip.trips where idempotency_key is not null")"
DUPES="$(sql "select count(*) from (select 1 from atlas_trip.trips where idempotency_key is not null group by rider_id, idempotency_key having count(*) > 1) d")"
echo "duplicate (rider, key) groups: $DUPES"
[ "$DUPES" = "0" ] || { echo "FAIL"; exit 1; }
echo "PASS"
