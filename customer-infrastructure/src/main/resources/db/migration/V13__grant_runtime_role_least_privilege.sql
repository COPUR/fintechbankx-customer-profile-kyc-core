-- Flyway runs as the migration owner (DB_MIGRATION_USERNAME, the db-migration
-- secret), which owns sc_cus_profile_kyc and every table in it, in the chart's
-- pre-install/pre-upgrade Job (decision 0001). The service pods connect as the
-- runtime role (${runtime_role}, from DB_USERNAME) and get only what the
-- service does:
--   customer               SELECT, INSERT, UPDATE (profiles, credit position, KYC status; the service
--                          never deletes a customer: no DELETE, TRUNCATE)
--   credit_movement        SELECT, INSERT (the idempotency journal is append-only: no UPDATE, DELETE)
--   credit_reservation     SELECT, INSERT, UPDATE (released_amount moves; rows are never removed)
--   outbox_event           SELECT, INSERT, UPDATE, DELETE (the relay marks, parks and purges rows;
--                          created_seq is an identity column, so INSERT needs no sequence grant)
--   flyway_schema_history  SELECT (the service validates the schema history at startup and
--                          refuses to start while a migration is pending; it cannot record,
--                          repair or remove one; Flyway's default table name, application.yml
--                          does not change it)
-- Not being the owner, it can neither ALTER nor DROP the tables, and it cannot
-- create objects in the schema. Every later migration that adds a table grants
-- the runtime role explicitly.
--
-- Local single-user runs (no DB_MIGRATION_USERNAME) migrate as the runtime
-- role itself; then there is nothing to separate and this migration only says
-- so, because revoking the owner's own privileges would break later migrations.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the migration owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);

    EXECUTE format('REVOKE ALL ON TABLE customer, credit_movement, credit_reservation, outbox_event, flyway_schema_history FROM %I, PUBLIC',
                   runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE customer TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE credit_movement TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE credit_reservation TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_event TO %I', runtime_role);
    EXECUTE format('GRANT SELECT ON TABLE flyway_schema_history TO %I', runtime_role);
END
$$;
