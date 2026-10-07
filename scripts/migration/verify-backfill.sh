#!/usr/bin/env bash
# Rehearses the monolith -> svc-cus-profile-kyc data split end to end on a
# scratch PostgreSQL: builds a monolith-shaped source, applies this service's
# Flyway migrations to a separate database, runs db/backfill/run-backfill.sh
# twice (the second run proves it is idempotent) and checks the mapped values.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_customer_backfill_source"
dst_db="cus_backfill_target"
schema="sc_cus_profile_kyc"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA $schema"
for migration in "$root"/customer-infrastructure/src/main/resources/db/migration/V*.sql; do
  PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f "$migration"
done

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" AED
done

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

check "customers loaded once despite two runs" \
  "SELECT count(*) FROM $schema.customer" "3"
check "customer id is the monolith id as text, the id loans and payments use" \
  "SELECT string_agg(customer_id || ':' || legacy_customer_id, ',' ORDER BY legacy_customer_id) FROM $schema.customer" "1:1,2:2,3:3"
check "names, credit position, currency and version carried" \
  "SELECT first_name || ' ' || last_name || ' ' || currency || ' ' || credit_limit::numeric(19,2) || ' ' || used_credit::numeric(19,2) || ' ' || version FROM $schema.customer WHERE customer_id = '1'" "Amina Haddad AED 50000.00 20000.00 3"
check "contact, score and income stay empty (the monolith never had them)" \
  "SELECT count(*) FROM $schema.customer WHERE email IS NULL AND phone_number IS NULL AND credit_score IS NULL AND monthly_income IS NULL" "3"
check "no loan or payment tables in the customer schema" \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$schema' AND (table_name LIKE 'loan%' OR table_name LIKE 'payment%')" "0"

echo "Backfill rehearsal passed."
