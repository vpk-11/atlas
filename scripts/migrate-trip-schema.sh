#!/usr/bin/env bash
# One-off upgrade for a trips table created before per-rider keys, leases and claim tokens
# (ddl-auto: update adds columns and the new index but never drops the old global unique
# index or backfills). Run it after the new trip-service has started. Safe to rerun.
# Skip it entirely by deleting the MySQL PVC (drop-and-reseed).
set -euo pipefail

NAMESPACE=atlas
PASSWORD="$(kubectl -n "$NAMESPACE" get secret mysql-credentials -o jsonpath='{.data.root-password}' | base64 -d)"

kubectl -n "$NAMESPACE" exec -i deploy/mysql -- mysql -uroot -p"$PASSWORD" atlas_trip 2>/dev/null <<'SQL'
-- 1. Drop the old unique index that covers idempotency_key alone (keys were global).
SET @old_index = (SELECT index_name FROM information_schema.statistics
                  WHERE table_schema = 'atlas_trip' AND table_name = 'trips'
                  GROUP BY index_name
                  HAVING COUNT(*) = 1 AND MAX(non_unique) = 0 AND MAX(column_name) = 'idempotency_key'
                  LIMIT 1);
SET @drop_sql = IF(@old_index IS NULL, 'SELECT "no legacy global key index"',
                   CONCAT('ALTER TABLE trips DROP INDEX `', @old_index, '`'));
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- 2. Backfill the lease and fencing token on rows written before they existed.
-- A NULL claimed_at never compares below a cutoff, so the sweep and replay claims skip such rows.
UPDATE trips SET claimed_at = created_at WHERE claimed_at IS NULL;
UPDATE trips SET claim_token = UUID() WHERE claim_token IS NULL;

SELECT CONCAT('legacy index dropped: ', IFNULL(@old_index, 'none found')) AS result;
SELECT COUNT(*) AS rows_without_lease_or_token FROM trips WHERE claimed_at IS NULL OR claim_token IS NULL;
SQL
