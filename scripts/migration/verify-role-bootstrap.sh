#!/usr/bin/env bash
# Rehearses the DBA bootstrap of this service's database roles
# (db/bootstrap/roles.sql) on a scratch PostgreSQL:
#   - runs it as a non-superuser role with CREATEROLE that owns the database
#     (like the RDS master user), twice: the second run must change nothing;
#   - checks both roles (LOGIN, no password, no elevated attributes, the
#     runtime role not a member of the owner), the schema owner, and the
#     database privileges;
#   - applies the Flyway migrations as the owner, with the runtime role as the
#     runtime_role placeholder (as the migration Job does), runs the bootstrap
#     a third time and checks the runtime role has exactly V13's grants;
#   - checks the bootstrap refuses a runtime role that is a member of the owner.
#
# usage: verify-role-bootstrap.sh [database]   (default cus_role_bootstrap)
# Needs psql and a superuser via the usual PG* env vars (PGHOST, PGPORT,
# PGUSER, PGPASSWORD). Role names come from ROLE_PREFIX (default
# cus_rb), so the rehearsal never touches real roles; everything it creates
# is dropped on exit.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
db="${1:-cus_role_bootstrap}"
prefix="${ROLE_PREFIX:-cus_rb}"
dba="${prefix}_dba"
owner="${prefix}_owner"
runtime="${prefix}_app"
schema="sc_cus_profile_kyc"
bootstrap="$root/db/bootstrap/roles.sql"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

cleanup() {
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" >/dev/null 2>&1 || true
  for role in "$runtime" "$owner" "$dba"; do
    psql_q -d postgres -c "DROP ROLE IF EXISTS $role" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT
cleanup

test -f "$bootstrap" || { echo "FAIL $bootstrap is missing" >&2; exit 1; }
# No credential in the file: passwords are set out of band (psql \password).
if grep -nEi "password[[:space:]]*('|:'|:\"|=)|encrypted[[:space:]]+password" "$bootstrap" | grep -v '^[0-9]*:[[:space:]]*--'; then
  echo "FAIL $bootstrap sets a password" >&2; exit 1
fi

psql_q -d postgres -c "CREATE ROLE $dba LOGIN CREATEROLE" -c "CREATE DATABASE $db OWNER $dba"

# As the DBA (current_user = the DBA role, not a superuser).
run_bootstrap() {
  PGOPTIONS="-c role=$dba" psql_q -d "$db" -v owner_role="$owner" -v runtime_role="$runtime" -f "$bootstrap"
}

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

snapshot() {
  psql -X -At -d "$db" \
    -c "SELECT string_agg(r.rolname || ':' || r.rolcanlogin || r.rolsuper || r.rolcreaterole || r.rolcreatedb || r.rolreplication
                          || r.rolbypassrls || ':' || coalesce(a.rolpassword, '-'), ',' ORDER BY r.rolname)
          FROM pg_roles r JOIN pg_authid a ON a.oid = r.oid WHERE r.rolname IN ('$owner', '$runtime')" \
    -c "SELECT nspowner::regrole || ' ' || coalesce(nspacl::text, '-') FROM pg_namespace WHERE nspname = '$schema'" \
    -c "SELECT coalesce(datacl::text, '-') FROM pg_database WHERE datname = current_database()" \
    -c "SELECT coalesce(nspacl::text, '-') FROM pg_namespace WHERE nspname = 'public'" \
    -c "SELECT string_agg(m.roleid::regrole || '>' || m.member::regrole, ',' ORDER BY 1) FROM pg_auth_members m
          WHERE m.member::regrole::text IN ('$owner', '$runtime') OR m.roleid::regrole::text IN ('$owner', '$runtime')"
}

echo "--- bootstrap run 1"
run_bootstrap
first="$(snapshot)"
echo "--- bootstrap run 2"
run_bootstrap
[ "$(snapshot)" = "$first" ] || { echo "FAIL the second bootstrap run changed roles or privileges" >&2; diff <(echo "$first") <(snapshot) >&2; exit 1; }
echo "ok   a second run changes nothing"

check "both roles can log in, without a password, with no elevated attribute" \
  "SELECT string_agg(r.rolname || ' ' || r.rolcanlogin || ' ' || (a.rolpassword IS NULL) || ' ' || (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls), ',' ORDER BY r.rolname)
     FROM pg_roles r JOIN pg_authid a ON a.oid = r.oid WHERE r.rolname IN ('$owner', '$runtime')" \
  "$runtime true true false,$owner true true false"
check "the runtime role is not a member of the owner" "SELECT pg_has_role('$runtime', '$owner', 'MEMBER')" "f"
check "the owner owns $schema" "SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = '$schema'" "$owner"
check "the owner may connect and create schemas (backfill_stage)" \
  "SELECT has_database_privilege('$owner', current_database(), 'CONNECT') AND has_database_privilege('$owner', current_database(), 'CREATE')" "t"
check "the runtime role may connect, not create schemas" \
  "SELECT has_database_privilege('$runtime', current_database(), 'CONNECT') || ' ' || has_database_privilege('$runtime', current_database(), 'CREATE')" "true false"
check "before V13 the runtime role has nothing on $schema" \
  "SELECT has_schema_privilege('$runtime', '$schema', 'USAGE') OR has_schema_privilege('$runtime', '$schema', 'CREATE')" "f"
check "nobody but the owner creates in schema public" "SELECT has_schema_privilege('$runtime', 'public', 'CREATE')" "f"

# Migrate as the owner, as the migration Job does (Flyway creates its history
# table; a stub stands in for it here so V13 can grant SELECT on it).
migrations="$root/customer-infrastructure/src/main/resources/db/migration"
as_owner() { PGOPTIONS="-c role=$owner -c search_path=$schema" psql_q -d "$db" "$@"; }
as_owner -c "CREATE TABLE flyway_schema_history (installed_rank integer PRIMARY KEY)"
find "$migrations" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime/g" "$migration" | as_owner -f -
done
echo "--- bootstrap run 3 (after the migrations)"
run_bootstrap
check "the owner still owns $schema and every table in it" \
  "SELECT count(*) FILTER (WHERE tableowner <> '$owner') FROM pg_tables WHERE schemaname = '$schema'" "0"
check "the runtime role has exactly V13's grants" \
  "SELECT string_agg(table_name || ':' || privileges, ' ' ORDER BY table_name) FROM (
     SELECT table_name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges
       FROM information_schema.role_table_grants WHERE grantee = '$runtime' AND table_schema = '$schema' GROUP BY table_name) g" \
  "credit_movement:INSERT,SELECT credit_reservation:INSERT,SELECT,UPDATE customer:INSERT,SELECT,UPDATE flyway_schema_history:SELECT outbox_event:DELETE,INSERT,SELECT,UPDATE"
check "the runtime role uses $schema but cannot create in it" \
  "SELECT has_schema_privilege('$runtime', '$schema', 'USAGE') || ' ' || has_schema_privilege('$runtime', '$schema', 'CREATE')" "true false"

# A runtime role that holds the owner's privileges must stop the bootstrap.
psql_q -d postgres -c "GRANT $owner TO $runtime"
if run_bootstrap > /dev/null 2>&1; then
  echo "FAIL the bootstrap accepted a runtime role that is a member of the owner" >&2; exit 1
fi
echo "ok   a runtime role that is a member of the owner is refused"
psql_q -d postgres -c "REVOKE $owner FROM $runtime"
echo "role bootstrap rehearsal passed"
