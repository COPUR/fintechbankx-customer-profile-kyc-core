#!/usr/bin/env bash
# Rehearses the monolith -> svc-cus-profile-kyc data split end to end on a
# scratch PostgreSQL: builds a monolith-shaped source, applies this service's
# Flyway migrations to a separate database, runs db/backfill/run-backfill.sh
# twice (the second run proves it is idempotent), rehearses runbook step 3a
# (credit_reservation rows set used_credit; the monolith's used_credit_limit is
# never copied) and checks the mapped values.
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
migrations="$root/customer-infrastructure/src/main/resources/db/migration"
# Flyway placeholders, filled the way Flyway fills them in the migration Job: the
# runtime role is the connecting user here (a single-user run, so V13 grants nothing).
runtime_role="${PGUSER:-$(id -un)}"
apply_migration() {
  sed "s/\${runtime_role}/$runtime_role/g" "$1" | PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f -
}
for migration in "$migrations"/V[1-6]__*.sql; do
  apply_migration "$migration"
done

# V7 adds the KYC status. Rows that exist when it runs: a migrated monolith
# customer becomes VERIFIED/MIGRATED as of the migration; a customer created in
# this service was never checked and stays PENDING/STAFF.
psql_q -d "$dst_db" -c "INSERT INTO $schema.customer (customer_id, first_name, last_name, currency, credit_limit, used_credit, legacy_customer_id, legacy_synced_version, created_at, updated_at, version) VALUES ('900', 'Pre', 'Migrated', 'USD', 1000, 0, 900, 0, now(), now(), 0), ('CUST-PRE00001', 'Pre', 'Service', 'USD', 1000, 0, NULL, NULL, now(), now(), 0)"
# Version order, as Flyway applies them: a shell glob puts V10 before V7.
find "$migrations" -name 'V*.sql' | sort -V | while read -r migration; do
  case "$(basename "$migration")" in V[1-6]__*) continue ;; esac
  apply_migration "$migration"
done

report="$(mktemp)"
trap 'rm -f "$report"' EXIT

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

check "V7: an existing migrated customer is VERIFIED/MIGRATED as of the migration" \
  "SELECT kyc_status || ' ' || kyc_source || ' ' || (kyc_verified_at IS NOT NULL) FROM $schema.customer WHERE customer_id = '900'" "VERIFIED MIGRATED true"
check "V7: an existing customer created in this service stays PENDING/STAFF" \
  "SELECT kyc_status || ' ' || kyc_source || ' ' || (kyc_verified_at IS NULL) FROM $schema.customer WHERE customer_id = 'CUST-PRE00001'" "PENDING STAFF true"
psql_q -d "$dst_db" -c "DELETE FROM $schema.customer WHERE customer_id IN ('900', 'CUST-PRE00001')"

# The ledger currency is a required decision (monolith evidence: USD); a run
# without it must refuse to start rather than label every limit with a default.
if "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" > "$report" 2>&1; then
  cat "$report"; echo "FAIL a backfill without the currency argument ran" >&2; exit 1
fi
grep -q "^usage:" "$report" || { cat "$report"; echo "FAIL a backfill without the currency argument did not print usage" >&2; exit 1; }
check "a backfill without the currency argument loads nothing" "SELECT count(*) FROM $schema.customer" "0"

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD
done

# Decision (runbook section 2): the transform writes used_credit = 0 and never
# copies customers.used_credit_limit; step 3a sets used_credit from the open
# credit_reservation rows. The fixture's non-zero used_credit_limit values must
# therefore all land as 0, with no problem rows.
check "a monolith used_credit_limit of 500.00 (and any other value) yields used_credit = 0" \
  "SELECT string_agg(customer_id || ':' || used_credit::numeric(19,2), ',' ORDER BY legacy_customer_id) FROM $schema.customer" "1:0.00,2:0.00,3:0.00"

# The monolith never bumps customers.version (no @Version on its CustomerEntity,
# only an updated_at trigger), so every monolith change below leaves it as is,
# except the last case, which proves a bumped version is caught too.
echo "--- monolith raises the limit of customer 2 by 1000 and moves its used credit, without bumping version, backfill run 3"
psql_q -d "$src_db" -c "UPDATE customers SET credit_limit = credit_limit + 1000, used_credit_limit = used_credit_limit + 1000, updated_at = updated_at + interval '1 minute' WHERE id = 2"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD
check "a monolith credit-limit change before cut-over is picked up by a re-run; the used credit is still not copied" \
  "SELECT credit_limit::numeric(19,2) || ' ' || used_credit::numeric(19,2) || ' ' || version FROM $schema.customer WHERE customer_id = '2'" "11000.00 0.00 0"

# Step 3a, as the runbook describes it: one credit_reservation row per open
# monolith loan (reference = loans.id, the principal, nothing released), then
# used_credit = the sum of the customer's open reservations. It does not bump
# the customer's version, so a later backfill run takes the UPDATE path on
# that row and must leave used_credit alone.
echo "--- step 3a rehearsal: two open monolith loans of customer 1 become reservations, used_credit = 20000; backfill run 3a"
psql_q -d "$dst_db" \
  -c "INSERT INTO $schema.credit_reservation (reservation_id, customer_id, reference, currency, reserved_amount, released_amount, created_at, updated_at) VALUES
        (gen_random_uuid(), '1', '101', 'USD', 15000.0000, 0, now(), now()),
        (gen_random_uuid(), '1', '102', 'USD',  5000.0000, 0, now(), now())" \
  -c "UPDATE $schema.customer SET used_credit = 20000 WHERE customer_id = '1'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD
check "a re-run after step 3a keeps the used_credit step 3a set (used_credit = open reservations passes)" \
  "SELECT used_credit::numeric(19,2) || ' ' || version FROM $schema.customer WHERE customer_id = '1'" "20000.00 3"

expect_problem() {
  local run="$1" customer="$2" reason="$3"
  if "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD > "$report" 2>&1; then
    cat "$report"; echo "FAIL run $run: backfill accepted customer $customer ($reason)" >&2; exit 1
  fi
  grep -q "^$customer $reason" "$report" \
    || { cat "$report"; echo "FAIL run $run: reconcile did not report customer $customer as '$reason'" >&2; exit 1; }
  echo "ok   run $run: customer $customer '$reason' fails reconciliation"
}

echo "--- used_credit of customer 1 is set to 19000 while its open reservations hold 20000: run 3b must fail"
psql_q -d "$dst_db" -c "UPDATE $schema.customer SET used_credit = 19000 WHERE customer_id = '1'"
expect_problem 3b 1 "used_credit differs from its open reservations"
psql_q -d "$dst_db" -c "UPDATE $schema.customer SET used_credit = 20000 WHERE customer_id = '1'"

echo "--- the service changes customers 1 and 3 (as JPA would: version + 1): an e-mail, and a 2500 reserve for customer 3; backfill run 4"
psql_q -d "$dst_db" -c "UPDATE $schema.customer SET email = 'amina@example.com', version = version + 1 WHERE customer_id = '1'" \
  -c "INSERT INTO $schema.credit_reservation (reservation_id, customer_id, reference, currency, reserved_amount, released_amount, created_at, updated_at) VALUES (gen_random_uuid(), '3', 'LN-NEW-1', 'USD', 2500.0000, 0, now(), now())" \
  -c "UPDATE $schema.customer SET used_credit = 2500, version = version + 1 WHERE customer_id = '3'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD

echo "--- the monolith moves used credit for customer 1, which the service changed: not a two-sided change (the column is never copied), run 4a must pass"
psql_q -d "$src_db" -c "UPDATE customers SET used_credit_limit = used_credit_limit + 900, updated_at = updated_at + interval '1 minute' WHERE id = 1"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD

echo "--- the monolith raises the limit of customer 1, which the service changed, without bumping version: run 5 must fail"
psql_q -d "$src_db" -c "UPDATE customers SET credit_limit = credit_limit + 900, updated_at = updated_at + interval '1 minute' WHERE id = 1"
expect_problem 5 1 "monolith changed it after the service did"

echo "--- the monolith also lowers the limit of customer 3 and bumps version: run 6 must fail"
psql_q -d "$src_db" -c "UPDATE customers SET credit_limit = credit_limit - 1, version = version + 1 WHERE id = 3"
expect_problem 6 3 "monolith changed it after the service did"

check "customers loaded once despite repeated runs" \
  "SELECT count(*) FROM $schema.customer" "3"
check "customer id is the monolith id as text, the id loans and payments use" \
  "SELECT string_agg(customer_id || ':' || legacy_customer_id, ',' ORDER BY legacy_customer_id) FROM $schema.customer" "1:1,2:2,3:3"
check "names, limit, currency and version carried; used credit is what step 3a set" \
  "SELECT first_name || ' ' || last_name || ' ' || currency || ' ' || credit_limit::numeric(19,2) || ' ' || used_credit::numeric(19,2) || ' ' || version FROM $schema.customer WHERE customer_id = '1'" "Amina Haddad USD 50000.00 20000.00 4"
check "contact, score and income stay empty unless the service set them" \
  "SELECT count(*) FROM $schema.customer WHERE email IS NULL AND phone_number IS NULL AND credit_score IS NULL AND monthly_income IS NULL" "2"
check "a re-run never overwrites a customer the service changed" \
  "SELECT used_credit::numeric(19,2) || ' ' || version FROM $schema.customer WHERE customer_id = '3'" "2500.00 8"
check "the conflicting monolith limit change did not reach the service copy" \
  "SELECT credit_limit::numeric(19,2) FROM $schema.customer WHERE customer_id = '1'" "50000.00"
check "every migrated customer's used_credit equals its open reservations" \
  "SELECT count(*) FROM $schema.customer c WHERE c.legacy_customer_id IS NOT NULL AND c.used_credit = (SELECT coalesce(sum(reserved_amount - released_amount), 0) FROM $schema.credit_reservation r WHERE r.customer_id = c.customer_id)" "3"
check "backfilled monolith customers are KYC VERIFIED/MIGRATED with a verification time" \
  "SELECT count(*) FROM $schema.customer WHERE kyc_status = 'VERIFIED' AND kyc_source = 'MIGRATED' AND kyc_verified_at IS NOT NULL AND kyc_updated_by IS NULL" "3"
check "no loan or payment tables in the customer schema" \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$schema' AND (table_name LIKE 'loan%' OR table_name LIKE 'payment%')" "0"

echo "Backfill rehearsal passed."
