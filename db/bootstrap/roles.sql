-- DBA bootstrap of svc-cus-profile-kyc's database roles (runbook section 1,
-- "Database roles"). Run once per environment before the first deploy, with
-- psql, connected to db_cus_profile_kyc_<env> as the RDS master user (or any
-- role with CREATEROLE that owns the database, or a superuser):
--
--   psql -X -v ON_ERROR_STOP=1 -d db_cus_profile_kyc_<env> -f db/bootstrap/roles.sql
--
-- Idempotent: a second run changes nothing, and running it after the
-- migrations keeps the owner's objects and V13's grants as they are.
--
-- It creates (or checks) two LOGIN roles:
--   owner_role    customer_profile_owner: the schema owner. Owns
--                 sc_cus_profile_kyc; CONNECT and CREATE on the database
--                 (Flyway in the migration Job, the backfill's
--                 backfill_stage schema). Its credential is the
--                 <env>/customer-profile-kyc-service/db-migration secret.
--   runtime_role  customer_profile_app: the service pods (DB_USERNAME).
--                 CONNECT only; everything else comes from the migrations
--                 (V13__grant_runtime_role_least_privilege.sql: USAGE on the
--                 schema and per-table grants). Never a member of the owner.
--                 Its credential is the .../db-app secret.
-- Neither gets SUPERUSER, CREATEDB, CREATEROLE, REPLICATION or BYPASSRLS; an
-- existing role that has one stops the run, as does a runtime role that is a
-- member of the owner.
--
-- No password is set here. Set each one out of band, then write
-- {"username","password"} to the two Secrets Manager secrets Terraform
-- creates (app_db_secret_name, migration_db_secret_name):
--   psql -d db_cus_profile_kyc_<env> -c '\password customer_profile_owner'
--   psql -d db_cus_profile_kyc_<env> -c '\password customer_profile_app'
-- (\password prompts and sends only a SCRAM hash.)
--
-- The role names can be overridden with psql variables (-v owner_role=...
-- -v runtime_role=...); the rehearsal (scripts/migration/verify-role-bootstrap.sh)
-- uses scratch names. The schema name is fixed: it is Flyway's schema in
-- application.yml. The terraform-modules aurora-postgresql module is to
-- ship only a generic role_bootstrap_sql output (proposed), which cannot
-- know this schema or V13's split; run this file instead.

\set ON_ERROR_STOP on
\if :{?owner_role}
\else
\set owner_role customer_profile_owner
\endif
\if :{?runtime_role}
\else
\set runtime_role customer_profile_app
\endif

SET fbx_bootstrap.owner_role = :'owner_role';
SET fbx_bootstrap.runtime_role = :'runtime_role';

DO $bootstrap$
DECLARE
    owner_role   text := current_setting('fbx_bootstrap.owner_role');
    runtime_role text := current_setting('fbx_bootstrap.runtime_role');
    schema_name  constant text := 'sc_cus_profile_kyc';
    role_name    text;
    elevated     boolean;
BEGIN
    IF owner_role = runtime_role THEN
        RAISE EXCEPTION 'owner_role and runtime_role must differ (both %)', owner_role;
    END IF;

    -- 1. Both roles: LOGIN, no password, no elevated attribute. CREATE ROLE
    --    defaults every attribute to NO*; an existing role is not altered
    --    beyond LOGIN (a non-superuser may not even name SUPERUSER), only
    --    checked.
    FOREACH role_name IN ARRAY ARRAY[owner_role, runtime_role] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN', role_name);
            RAISE NOTICE 'created role % (LOGIN, no password: set it with \password)', role_name;
        ELSE
            EXECUTE format('ALTER ROLE %I LOGIN', role_name);
        END IF;
        SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
          INTO elevated FROM pg_roles WHERE rolname = role_name;
        IF elevated THEN
            RAISE EXCEPTION 'role % has SUPERUSER, CREATEDB, CREATEROLE, REPLICATION or BYPASSRLS; remove it first', role_name;
        END IF;
    END LOOP;

    -- 2. The runtime role must not hold the owner's privileges, directly or
    --    through another role.
    IF pg_has_role(runtime_role, owner_role, 'MEMBER') THEN
        RAISE EXCEPTION 'runtime role % is a member of the owner %: REVOKE % FROM % first',
            runtime_role, owner_role, owner_role, runtime_role;
    END IF;

    -- 3. Handing the schema to the owner needs the right to SET ROLE to it.
    --    A superuser has it; on PostgreSQL 16+ a CREATEROLE creator (the RDS
    --    master user) holds ADMIN on the roles it created but not SET, so it
    --    grants itself SET (without INHERIT); before 16 plain membership is
    --    the SET right.
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        IF current_setting('server_version_num')::int >= 160000 THEN
            IF NOT pg_has_role(current_user, owner_role, 'SET') THEN
                EXECUTE format('GRANT %I TO CURRENT_USER WITH INHERIT FALSE, SET TRUE', owner_role);
            END IF;
        ELSIF NOT pg_has_role(current_user, owner_role, 'MEMBER') THEN
            EXECUTE format('GRANT %I TO CURRENT_USER', owner_role);
        END IF;
    END IF;

    -- 4. Database: both connect; the owner also creates schemas
    --    (sc_cus_profile_kyc when Flyway finds none, backfill_stage).
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', current_database(), owner_role);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), runtime_role);

    -- 5. Nobody creates objects in schema public (already the default from
    --    PostgreSQL 15 on; the database belongs to this service alone).
    EXECUTE 'REVOKE CREATE ON SCHEMA public FROM PUBLIC';

    -- 6. The owner owns the service schema. The runtime role gets nothing on
    --    it here: V13 grants USAGE and the per-table privileges.
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', schema_name, owner_role);
    EXECUTE format('ALTER SCHEMA %I OWNER TO %I', schema_name, owner_role);
END
$bootstrap$;

RESET fbx_bootstrap.owner_role;
RESET fbx_bootstrap.runtime_role;
