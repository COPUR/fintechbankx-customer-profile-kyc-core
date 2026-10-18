-- DatabaseMigrationIT only: a migration the Job has not applied yet. The
-- service validates at startup and must refuse to start while it is pending;
-- it is never applied.
select 1;
