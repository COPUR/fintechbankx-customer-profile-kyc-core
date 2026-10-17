#!/usr/bin/env bash
# Runbook step 3a: rebuilds each migrated customer's credit position from the
# monolith's open loans, after run-backfill.sh has reconciled under the freeze.
# The monolith never stored a credit position (credit moved in an in-memory map;
# customers.used_credit_limit is a seed value), so the backfill starts every
# migrated customer at used_credit = 0. This script:
#
#   1. reads the monolith's open loans (NOT is_paid; V2 has no disbursement
#      column) minus the lending squad's never-disbursed list;
#   2. inserts one credit_reservation per loan: customer_id = loans.customer_id
#      as text, reference = loans.id, reserved_amount = loans.loan_amount (the
#      principal), released_amount = 0, currency = the run's currency; an
#      existing (customer_id, reference) row is kept and checked, not rewritten;
#   3. sets used_credit of every migrated customer to the sum of its open
#      reservations, without bumping version (a backfill re-run keeps it);
#   4. checks count and money total against the monolith, each reservation
#      against its loan, that no never-disbursed or paid loan holds one, and
#      that no customer goes over credit_limit.
#
# Everything runs in one transaction on the service database: any problem
# rolls the whole run back, so a failed run changes nothing.
#
#   db/backfill/step-3a-credit-reservations.sh [--rollback] <monolith-conninfo> <customer-service-conninfo> <currency> <never-disbursed-file>
#
# <currency> is required (ISO 4217), as for run-backfill.sh. <never-disbursed-file>
# is required and may be empty: one monolith loan id per line, blank lines and
# lines starting with # ignored. STEP3A_DRY_RUN=true runs every step and the
# checks, prints the figures and rolls back. --rollback deletes the reservations
# that name a monolith loan id and resets used_credit of migrated customers to
# 0, the value step 3 left (before routing, as the runbook's rollback column says).
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

usage() {
  echo "usage: $0 [--rollback] <monolith-conninfo> <customer-service-conninfo> <currency> <never-disbursed-file>" >&2
  exit 2
}

mode="apply"
if [ "${1:-}" = "--rollback" ]; then
  mode="rollback"
  shift
fi
[ "$#" -eq 4 ] || usage

source_db="$1"
target_db="$2"
currency="$3"
excluded_file="$4"
schema="sc_cus_profile_kyc"
dry_run="${STEP3A_DRY_RUN:-false}"

if ! [[ "$currency" =~ ^[A-Z]{3}$ ]]; then
  echo "currency must be an ISO 4217 code, got '$currency'" >&2
  exit 2
fi
if [[ "${source_db,,}" == *password* || "${target_db,,}" == *password* || "$source_db$target_db" == *://*:*@* ]]; then
  echo "conninfo must not carry a password; use PGPASSWORD or ~/.pgpass" >&2
  exit 2
fi
if [ ! -r "$excluded_file" ]; then
  echo "never-disbursed list '$excluded_file' not readable: the lending squad's list is required (an empty file when there are none)" >&2
  exit 2
fi
if [ "$dry_run" != "true" ] && [ "$dry_run" != "false" ]; then
  echo "STEP3A_DRY_RUN must be true or false, got '$dry_run'" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$excluded_file" | tr -d '[:blank:]\r' > "$work/excluded.csv" || true

echo "Exporting loans from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT id, customer_id, loan_amount, is_paid FROM loans ORDER BY id) TO '$work/loans.csv' WITH (FORMAT csv)
COMMIT;
SQL

finish="COMMIT"
[ "$dry_run" = "true" ] && finish="ROLLBACK"

if [ "$mode" = "rollback" ]; then
  run "$target_db" <<SQL
BEGIN;
SET LOCAL search_path = $schema;
CREATE TEMP TABLE step3a_loan (id text PRIMARY KEY, customer_id bigint, loan_amount numeric(19,2), is_paid boolean) ON COMMIT DROP;
\copy step3a_loan FROM '$work/loans.csv' WITH (FORMAT csv)
DELETE FROM credit_reservation r USING customer c, step3a_loan l
 WHERE r.customer_id = c.customer_id AND c.legacy_customer_id = l.customer_id AND r.reference = l.id;
UPDATE customer SET used_credit = 0 WHERE legacy_customer_id IS NOT NULL;
\echo rollback: reservations of monolith loans deleted, used_credit of migrated customers reset to 0
$finish;
SQL
  exit 0
fi

echo "Inserting reservations, setting used_credit and checking (one transaction)..."
run "$target_db" -At -v currency="$currency" <<SQL
BEGIN;
SET LOCAL search_path = $schema;
CREATE TEMP TABLE step3a_loan (id text PRIMARY KEY, customer_id bigint, loan_amount numeric(19,2), is_paid boolean) ON COMMIT DROP;
CREATE TEMP TABLE step3a_excluded (id text PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE step3a_problem (line text) ON COMMIT DROP;
\copy step3a_loan FROM '$work/loans.csv' WITH (FORMAT csv)
\copy step3a_excluded FROM '$work/excluded.csv' WITH (FORMAT csv)

-- The loans this step reserves for: open and not on the never-disbursed list.
CREATE TEMP VIEW step3a_open AS
  SELECT l.* FROM step3a_loan l WHERE NOT l.is_paid AND l.id NOT IN (SELECT id FROM step3a_excluded);

INSERT INTO step3a_problem
  SELECT e.id || ' never-disbursed list names no open monolith loan' FROM step3a_excluded e
   WHERE NOT EXISTS (SELECT 1 FROM step3a_loan l WHERE l.id = e.id AND NOT l.is_paid);
INSERT INTO step3a_problem
  SELECT o.id || ' customer ' || o.customer_id || ' is not a migrated customer' FROM step3a_open o
   WHERE NOT EXISTS (SELECT 1 FROM customer c WHERE c.legacy_customer_id = o.customer_id AND c.customer_id = o.customer_id::text);

INSERT INTO credit_reservation (reservation_id, customer_id, reference, currency, reserved_amount, released_amount, created_at, updated_at)
  SELECT gen_random_uuid(), c.customer_id, o.id, :'currency', o.loan_amount, 0, now(), now()
    FROM step3a_open o JOIN customer c ON c.legacy_customer_id = o.customer_id AND c.customer_id = o.customer_id::text
  ON CONFLICT (customer_id, reference) DO NOTHING;

-- Each reservation against its loan.
INSERT INTO step3a_problem
  SELECT o.id || CASE
           WHEN r.reservation_id IS NULL THEN ' has no reservation'
           WHEN r.reserved_amount <> o.loan_amount THEN ' reserved_amount ' || r.reserved_amount::numeric(19,2) || ' differs from loan_amount ' || o.loan_amount
           WHEN r.released_amount <> 0 THEN ' released_amount ' || r.released_amount::numeric(19,2) || ' is not 0'
           ELSE ' currency ' || r.currency || ' differs from the run''s ' || :'currency' END
    FROM step3a_open o
    JOIN customer c ON c.legacy_customer_id = o.customer_id AND c.customer_id = o.customer_id::text
    LEFT JOIN credit_reservation r ON r.customer_id = c.customer_id AND r.reference = o.id
   WHERE r.reservation_id IS NULL OR r.reserved_amount <> o.loan_amount OR r.released_amount <> 0 OR r.currency <> :'currency';
-- No reservation for a paid or never-disbursed monolith loan.
INSERT INTO step3a_problem
  SELECT l.id || CASE WHEN l.is_paid THEN ' paid loan has a reservation' ELSE ' never-disbursed loan has a reservation' END
    FROM step3a_loan l
    JOIN customer c ON c.legacy_customer_id = l.customer_id
    JOIN credit_reservation r ON r.customer_id = c.customer_id AND r.reference = l.id
   WHERE l.id NOT IN (SELECT id FROM step3a_open);

-- Positions: checked before the update, which ck_customer_credit would refuse.
CREATE TEMP TABLE step3a_position ON COMMIT DROP AS
  SELECT c.customer_id, c.credit_limit,
         coalesce((SELECT sum(r.reserved_amount - r.released_amount) FROM credit_reservation r WHERE r.customer_id = c.customer_id), 0) AS open_amount
    FROM customer c WHERE c.legacy_customer_id IS NOT NULL;
INSERT INTO step3a_problem
  SELECT customer_id || ' open reservations ' || open_amount::numeric(19,2) || ' exceed credit_limit ' || credit_limit::numeric(19,2)
    FROM step3a_position WHERE open_amount > credit_limit;
UPDATE customer c SET used_credit = p.open_amount
  FROM step3a_position p WHERE c.customer_id = p.customer_id AND p.open_amount <= p.credit_limit AND c.used_credit <> p.open_amount;

SELECT 'open loans  count|amount: ' || count(*) || '|' || coalesce(sum(loan_amount), 0)::numeric(19,2) FROM step3a_open;
SELECT 'reservations count|amount: ' || count(*) || '|' || coalesce(sum(r.reserved_amount), 0)::numeric(19,2)
  FROM credit_reservation r JOIN customer c ON c.customer_id = r.customer_id
  JOIN step3a_open o ON o.id = r.reference AND o.customer_id = c.legacy_customer_id;
INSERT INTO step3a_problem
  SELECT 'totals: open loans ' || a.n || '|' || a.s || ', reservations ' || b.n || '|' || b.s
    FROM (SELECT count(*) n, coalesce(sum(loan_amount), 0)::numeric(19,2) s FROM step3a_open) a,
         (SELECT count(*) n, coalesce(sum(r.reserved_amount), 0)::numeric(19,2) s
            FROM credit_reservation r JOIN customer c ON c.customer_id = r.customer_id
            JOIN step3a_open o ON o.id = r.reference AND o.customer_id = c.legacy_customer_id) b
   WHERE a.n <> b.n OR a.s <> b.s;

SELECT line FROM step3a_problem ORDER BY line;
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM step3a_problem) THEN
    RAISE EXCEPTION 'STEP 3A FAILED: % problem(s) listed above; nothing was changed', (SELECT count(*) FROM step3a_problem);
  END IF;
END
\$\$;
$finish;
SQL

if [ "$dry_run" = "true" ]; then
  echo "dry run: rolled back, nothing written"
else
  echo "Step 3a reconciled."
fi
