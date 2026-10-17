#!/usr/bin/env bash
# Rehearses runbook step 3a (db/backfill/step-3a-credit-reservations.sh) on a
# scratch PostgreSQL: a monolith-shaped source with customers and loans, this
# service's migrations, the customer backfill, then step 3a. Checks the dry
# run, two real runs (idempotent), the never-disbursed list, the rollback, and
# that a mismatched reservation, an unmigrated customer, a customer over their
# limit, a wrong currency and a typo in the list each fail and change nothing.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
step="$root/db/backfill/step-3a-credit-reservations.sh"
src_db="elms_step3a_source"
dst_db="cus_step3a_target"
schema="sc_cus_profile_kyc"
src="dbname=$src_db"
dst="dbname=$dst_db"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done
psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql" -f "$root/db/backfill/test/monolith_loans_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA $schema"
runtime_role="${PGUSER:-$(id -un)}"
find "$root/customer-infrastructure/src/main/resources/db/migration" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime_role/g" "$migration" | PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f -
done
"$root/db/backfill/run-backfill.sh" "$src" "$dst" USD > /dev/null

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
report="$work/report"
excluded="$work/never-disbursed.txt"
printf '# never-disbursed loans, from the lending squad\n201\n\n' > "$excluded"
: > "$work/empty.txt"

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}
positions() {
  check "$1" "SELECT string_agg(customer_id || ':' || used_credit::numeric(19,2), ',' ORDER BY legacy_customer_id) || ' ' ||
    (SELECT count(*) || '/' || coalesce(sum(reserved_amount), 0)::numeric(19,2) FROM $schema.credit_reservation) FROM $schema.customer" "$2"
}
expect_refusal() {
  local label="$1" pattern="$2"; shift 2
  if "$@" > "$report" 2>&1; then
    cat "$report"; echo "FAIL $label: the run succeeded" >&2; exit 1
  fi
  grep -q -- "$pattern" "$report" || { cat "$report"; echo "FAIL $label: no '$pattern' in the output" >&2; exit 1; }
  echo "ok   $label"
}

expect_refusal "a run without arguments prints usage" "^usage:" "$step"
expect_refusal "a run without the never-disbursed list refuses" "never-disbursed" "$step" "$src" "$dst" USD "$work/missing.txt"
expect_refusal "a password in the conninfo refuses" "must not carry a password" "$step" "$src passw""ord=redacted" "$dst" USD "$excluded"
expect_refusal "a currency that is not ISO 4217 refuses" "ISO 4217" "$step" "$src" "$dst" usd "$excluded"
positions "nothing written before step 3a" "1:0.00,2:0.00,3:0.00 0/0.00"

STEP3A_DRY_RUN=true "$step" "$src" "$dst" USD "$excluded" > "$report"
grep -q "dry run: rolled back" "$report" || { cat "$report"; echo "FAIL the dry run did not say it rolled back" >&2; exit 1; }
grep -q "open loans  count|amount: 3|45000.00" "$report" || { cat "$report"; echo "FAIL the dry run did not print the monolith figures" >&2; exit 1; }
positions "a dry run reconciles and writes nothing" "1:0.00,2:0.00,3:0.00 0/0.00"

for run in 1 2; do
  echo "--- step 3a run $run"
  "$step" "$src" "$dst" USD "$excluded"
  positions "run $run: one reservation per open disbursed loan, used_credit = open reservations" "1:20000.00,2:0.00,3:25000.00 3/45000.00"
done
check "reservations carry the loan id, the principal, nothing released and the run currency" \
  "SELECT string_agg(customer_id || ':' || reference || ':' || reserved_amount::numeric(19,2) || ':' || released_amount::numeric(19,2) || ':' || currency, ',' ORDER BY reference) FROM $schema.credit_reservation" \
  "1:101:15000.00:0.00:USD,1:102:5000.00:0.00:USD,3:301:25000.00:0.00:USD"
check "step 3a keeps the version the backfill carried from the monolith" "SELECT string_agg(version::text, ',' ORDER BY legacy_customer_id) FROM $schema.customer" "3,0,7"

expect_refusal "an id on the list that is no open monolith loan fails" "^999 never-disbursed list names no open monolith loan" \
  "$step" "$src" "$dst" USD <(printf '201\n999\n')
expect_refusal "a run with another currency fails on the existing rows" "^101 currency USD differs from the run's EUR" "$step" "$src" "$dst" EUR "$excluded"

echo "--- reservation 101 holds 14000 instead of the principal"
psql_q -d "$dst_db" -c "UPDATE $schema.credit_reservation SET reserved_amount = 14000 WHERE reference = '101'"
expect_refusal "a reservation that differs from the loan's principal fails" "^101 reserved_amount 14000.00 differs from loan_amount 15000.00" "$step" "$src" "$dst" USD "$excluded"
psql_q -d "$dst_db" -c "UPDATE $schema.credit_reservation SET reserved_amount = 15000 WHERE reference = '101'"

echo "--- the monolith has customer 4 with an open loan, never backfilled"
psql_q -d "$src_db" -c "INSERT INTO customers (id, name, surname, credit_limit) VALUES (4, 'Test', 'Customer-Four', 5000)" \
  -c "INSERT INTO loans (id, customer_id, loan_amount, number_of_installments, interest_rate) VALUES ('401', 4, 1000, 6, 0.2)"
expect_refusal "an open loan of a customer the service does not have fails" "^401 customer 4 is not a migrated customer" "$step" "$src" "$dst" USD "$excluded"
psql_q -d "$src_db" -c "DELETE FROM loans WHERE id = '401'" -c "DELETE FROM customers WHERE id = 4"

echo "--- the monolith has a 6000 open loan for customer 2 (limit 10000) besides 201: with 201 left in, 9000 is still within the limit"
psql_q -d "$src_db" -c "INSERT INTO loans (id, customer_id, loan_amount, number_of_installments, interest_rate) VALUES ('202', 2, 6000, 6, 0.2)"
"$step" "$src" "$dst" USD "$work/empty.txt"
positions "an empty list keeps every open loan" "1:20000.00,2:9000.00,3:25000.00 5/54000.00"
expect_refusal "a never-disbursed loan that has a reservation fails" "^201 never-disbursed loan has a reservation" "$step" "$src" "$dst" USD "$excluded"
positions "a failed run changes nothing" "1:20000.00,2:9000.00,3:25000.00 5/54000.00"

echo "--- the rollback refuses once the service has moved credit (runbook step 5 or later)"
psql_q -d "$dst_db" -c "UPDATE $schema.credit_reservation SET released_amount = 1000, updated_at = updated_at + interval '1 minute' WHERE reference = '101'" \
  -c "UPDATE $schema.customer SET used_credit = used_credit - 1000 WHERE customer_id = '1'"
expect_refusal "a rollback refuses when a reservation of a monolith loan moved since step 3a" "^101 reservation moved since step 3a" "$step" --rollback "$src" "$dst" USD "$excluded"
psql_q -d "$dst_db" -c "UPDATE $schema.credit_reservation SET released_amount = 0, updated_at = created_at WHERE reference = '101'" \
  -c "UPDATE $schema.customer SET used_credit = used_credit + 1000 WHERE customer_id = '1'"
psql_q -d "$dst_db" -c "INSERT INTO $schema.credit_movement (movement_id, customer_id, idempotency_key, movement_type, currency, amount, reference, occurred_at) VALUES (gen_random_uuid(), '3', 'k-1', 'RESERVE', 'USD', 100, 'LN-NEW-1', now())"
expect_refusal "a rollback refuses when the service journalled a credit movement" "^3 the service journalled credit movements" "$step" --rollback "$src" "$dst" USD "$excluded"
psql_q -d "$dst_db" -c "DELETE FROM $schema.credit_movement"
positions "a refused rollback changes nothing" "1:20000.00,2:9000.00,3:25000.00 5/54000.00"

STEP3A_DRY_RUN=true "$step" --rollback "$src" "$dst" USD "$excluded" > "$report"
grep -q "dry run: would delete 5 reservation(s), rolled back" "$report" || { cat "$report"; echo "FAIL the dry-run rollback did not say what it would delete" >&2; exit 1; }
positions "a dry-run rollback writes nothing" "1:20000.00,2:9000.00,3:25000.00 5/54000.00"

echo "--- a reservation that names no monolith loan survives the rollback and still counts in used_credit"
psql_q -d "$dst_db" -c "INSERT INTO $schema.credit_reservation (reservation_id, customer_id, reference, currency, reserved_amount, released_amount, created_at, updated_at) VALUES (gen_random_uuid(), '3', 'LN-KEEP-1', 'USD', 2500, 0, now(), now())" \
  -c "UPDATE $schema.customer SET used_credit = used_credit + 2500 WHERE customer_id = '3'"
"$step" --rollback "$src" "$dst" USD "$excluded"
positions "the rollback recomputes used_credit from the reservations that remain" "1:0.00,2:0.00,3:2500.00 1/2500.00"
psql_q -d "$dst_db" -c "DELETE FROM $schema.credit_reservation WHERE reference = 'LN-KEEP-1'" -c "UPDATE $schema.customer SET used_credit = 0 WHERE customer_id = '3'"

echo "--- rollback done, then the loan of customer 2 grows to 8000: with 201 in, 11000 is over the 10000 limit"
positions "after the rollback every migrated position is back to 0" "1:0.00,2:0.00,3:0.00 0/0.00"
psql_q -d "$src_db" -c "UPDATE loans SET loan_amount = 8000 WHERE id = '202'"
expect_refusal "a customer whose open reservations exceed the credit limit fails" "^2 open reservations 11000.00 exceed credit_limit 10000.00" "$step" "$src" "$dst" USD "$work/empty.txt"
positions "the over-limit run changes nothing" "1:0.00,2:0.00,3:0.00 0/0.00"

"$step" "$src" "$dst" USD "$excluded"
positions "a run after the rollback rebuilds the positions" "1:20000.00,2:8000.00,3:25000.00 4/53000.00"
"$root/db/backfill/run-backfill.sh" "$src" "$dst" USD > /dev/null
positions "a backfill re-run after step 3a keeps used_credit (03_reconcile passes)" "1:20000.00,2:8000.00,3:25000.00 4/53000.00"

echo "Step 3a rehearsal passed."
