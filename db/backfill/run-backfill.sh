#!/usr/bin/env bash
# Copies customer data from the monolith database into svc-cus-profile-kyc's
# own database and reconciles the two. Re-runnable.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <customer-service-conninfo> [currency]
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <monolith-conninfo> <customer-service-conninfo> [currency]" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
currency="${3:-AED}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! [[ "$currency" =~ ^[A-Z]{3}$ ]]; then
  echo "currency must be an ISO 4217 code, got '$currency'" >&2
  exit 2
fi

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Exporting customers from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT id, name, surname, credit_limit, used_credit_limit, created_at, updated_at, version FROM customers ORDER BY id) TO '$work/customers.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SQL

echo "Staging and transforming into sc_cus_profile_kyc..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.customers FROM '$work/customers.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -v currency="$currency" -f "$here/02_transform_into_customer_service.sql"

echo "Reconciling..."
source_figures="$(psql -X -At "$source_db" -c "
  SELECT count(*), coalesce(sum(credit_limit), 0)::numeric(19,2), coalesce(sum(used_credit_limit), 0)::numeric(19,2) FROM customers")"
reconcile="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql")"
target_figures="$(echo "$reconcile" | sed -n '1p')"
invariant_breaks="$(echo "$reconcile" | sed -n '2,$p')"

echo "monolith         rows|limit|used: $source_figures"
echo "customer service rows|limit|used: $target_figures"
if [ "$source_figures" != "$target_figures" ]; then
  echo "RECONCILIATION FAILED: totals differ" >&2
  exit 1
fi
if [ -n "$invariant_breaks" ]; then
  echo "RECONCILIATION FAILED: customers breaking per-customer invariants:" >&2
  echo "$invariant_breaks" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
