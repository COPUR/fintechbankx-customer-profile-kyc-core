#!/usr/bin/env bash
# Copies customer data from the monolith database into svc-cus-profile-kyc's
# own database and reconciles the two. Re-runnable until cut-over: a re-run
# refreshes customers the service has not changed yet (see 02_*.sql) and never
# touches used_credit, which runbook step 3a sets from credit_reservation.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <customer-service-conninfo> <currency>
#
# <currency> is required, with no default: the monolith stored no currency, so
# the run labels every limit with it. The decision in force is USD (monolith
# evidence); svc-ln-loan-lifecycle's ledger currency must be the same.
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <monolith-conninfo> <customer-service-conninfo> <currency>   (ISO 4217, required; e.g. USD)" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
currency="$3"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! [[ "$currency" =~ ^[A-Z]{3}$ ]]; then
  echo "currency must be an ISO 4217 code, got '$currency'" >&2
  exit 2
fi

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

# used_credit_limit is left out on purpose: the live monolith never writes it
# (credit moves in an in-memory map), so the service starts every migrated
# customer at used_credit = 0 and runbook step 3a sets it from the open
# credit_reservation rows. See 02_transform_into_customer_service.sql.
echo "Exporting customers from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT id, name, surname, credit_limit, created_at, updated_at, version FROM customers ORDER BY id) TO '$work/customers.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT count(*), coalesce(sum(credit_limit), 0)::numeric(19,2) FROM customers) TO '$work/source_figures.csv' WITH (FORMAT csv, DELIMITER '|')
COMMIT;
SQL

echo "Staging and transforming into sc_cus_profile_kyc..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.customers FROM '$work/customers.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -v currency="$currency" -f "$here/02_transform_into_customer_service.sql"

echo "Reconciling..."
source_figures="$(cat "$work/source_figures.csv")"
reconcile="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql")"
target_figures="$(echo "$reconcile" | sed -n '1p')"
problems="$(echo "$reconcile" | sed -n '2,$p')"

echo "monolith snapshot rows|limit: $source_figures"
echo "staged copy       rows|limit: $target_figures"
if [ "$source_figures" != "$target_figures" ]; then
  echo "RECONCILIATION FAILED: totals differ" >&2
  exit 1
fi
if [ -n "$problems" ]; then
  echo "RECONCILIATION FAILED: customers with problems:" >&2
  echo "$problems" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
