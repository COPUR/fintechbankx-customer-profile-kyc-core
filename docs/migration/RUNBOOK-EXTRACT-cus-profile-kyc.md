# RUNBOOK-EXTRACT-cus-profile-kyc

Extraction of the Customer aggregate from `enterprise-loan-management-system`
into `svc-cus-profile-kyc` (this repository), following the strangler-fig
steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cus` / `svc-cus-profile-kyc` |
| Slice | Customer aggregate: profile, contact details, credit limit and credit reservations |
| Owned data | `db_cus_profile_kyc_<env>`, schema `sc_cus_profile_kyc`: `customer`, `credit_movement`, `credit_reservation`, `outbox_event` |
| Events | One topic per aggregate (ADR-019): `evt.cus.customer.v1`, key = customer id, headers `eventType`, `eventId`, `correlationId` (and `traceparent`); event types `Customer.Customer.{Created,ContactUpdated,CreditLimitUpdated,CreditReserved,CreditReleased,CreditScoreUpdated,KycStatusChanged}.v1`. Consumers skip event types they do not handle (commit the offset, no failure, no dead letter) (contract in this repo, `api/asyncapi/svc-cus-profile-kyc.yaml`; proposed to the catalog in fintechbankx-governance-api-contracts-asyncapi-catalog PR #9, which must merge before the relay is switched on) |
| Called by | `svc-ln-loan-lifecycle`: `GET /api/v1/customers/{id}/credit` (no personal data), `POST .../credit/reserve` and `.../credit/release` with a required `x-idempotency-key` and the loan id as `reference` |
| Caller identity | Client-credentials token with the `SERVICE` realm role, `aud` containing `svc-cus-profile-kyc` (Keycloak audience mapper) and `azp` on `SERVICE_CALLERS` (credit) or `SERVICE_CALLERS_KYC` (KYC status read) |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `customers` (V1) | this service, `customer` | id kept as text (`'42'`), which loans and payments already reference; numeric id kept in `legacy_customer_id` |
| `update_customers_updated_at` trigger | removed | `Customer` keeps `updated_at` |
| `customer_management` schema (V1__Create_customer_management_schema) | not migrated | parallel schema never used by the running application; confirm it has no rows before the cutover |
| `loans` (V2) | `svc-ln-loan-lifecycle` | loans keep `customer_id`; no foreign key across services |
| Credit reservations | this service, `credit_movement` (journal per idempotency key) and `credit_reservation` (V10, open amount per reference) | new: the monolith reserved credit in-process, so it had neither; at cut-over (step 3a) the squad backfills one `credit_reservation` row per open monolith loan (reference = loan id); any `used_credit` no backfilled reservation accounts for counts as untracked credit |

Flyway migrations for the owned tables: `customer-infrastructure/src/main/resources/db/migration/V1__create_customer_tables.sql` to `V13__grant_runtime_role_least_privilege.sql`. They are applied by the chart's migration Job as the schema owner, never by the service pods (section "Database migrations (Flyway Job)" below); V13 grants the runtime role (section "Database roles"). The service never reads monolith tables and the monolith must not read `sc_cus_profile_kyc`.

**Identity of migrated customers.** A migrated customer keeps the monolith id as text as its customer id (monolith `customers.id = 1` becomes `customer_id = '1'`), the id the loans and payments migrated by their own backfills already carry. The identity-link step (`PUT /api/v1/customers/{id}/identity-link`) sets the end user's Keycloak attribute `customer_id` to exactly that value, so the `customer_id` claim in the user's tokens equals the customer id, and the ownership checks here, in the loan service and in the payment service match the same id. `Customer.linkIdentity` accepts any id of 1 to 64 letters, digits or hyphens, so plain numeric ids such as `1` link (`CustomerIdentityLinkTest.aMigratedCustomerWithAPlainNumericIdCanBeLinked`). The parity seed customers (`CUST-12345678`, `CUST-87654321`, `CUST-11111111`, `db/fixtures/parity_seed_customers.sql`) are not monolith rows; the parity realm users' `customer_id` attributes must be exactly those ids.

**Release by reference (V10).** A release whose `reference` names one of the customer's reservations releases at most what that reservation still holds (`reserved_amount - released_amount`); partial releases are allowed, more is 422 `RELEASE_EXCEEDS_RESERVATION` (only while 0 < held < amount). A release that carries a `reference` matching no reservation, or naming a reservation that holds nothing any more (fully released; `svc-ln-loan-lifecycle`'s sweep relies on this answer), is always 422 `RESERVATION_NOT_FOUND`, whatever the amount and whatever untracked credit exists, so an unknown loan id (for example a loan auto-release for a loan this service never reserved for) can never touch migrated credit. Only a release **without** a reference releases untracked used credit, at most `used_credit` minus the sum of open reservations; more is 422 `RESERVATION_NOT_FOUND`. That covers balances migrated by the backfill that no reservation accounts for, without letting a release take another loan's reservation. The monolith floored used credit at zero instead (parity CU-09, an intended change). Reserves the loan service made through the monolith (before step 5) have no reservation here unless the cut-over creates one: step 3a backfills a `credit_reservation` row per open monolith loan, with the loan id as `reference`, so the loan service's referenced releases for those loans find them. Without step 3a every such release answers 422 `RESERVATION_NOT_FOUND` and the credit stays used. Races are decided by the customer row's optimistic version, as for reserve. To inspect a customer: `SELECT reference, reserved_amount, released_amount FROM sc_cus_profile_kyc.credit_reservation WHERE customer_id = '<id>'`; untracked credit is `used_credit` minus the sum of `reserved_amount - released_amount`.

The in-process `CustomerCreditSaga` (Spring `@EventListener` on loan and payment events) is removed. The loan service now reserves and releases credit synchronously over HTTP, idempotently, so a second asynchronous reservation path would double-count.

### Database roles (DBA bootstrap)

| Role | Used by | Privileges |
|---|---|---|
| migration owner (`customer_profile_owner`, secret `<env>/customer-profile-kyc-service/db-migration`, Terraform output `migration_db_secret_name`) | the Flyway migration Job only (`DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`, Helm `externalSecret.migrationSecretName`, required); the service pods never mount it | owns `sc_cus_profile_kyc` and its objects; needs `CREATE` on the database |
| `customer_profile_app` (runtime, `DB_USERNAME`, secret `<env>/customer-profile-kyc-service/db-app`) | the service | granted by V13 (Flyway placeholder `runtime_role`): `USAGE` on the schema; `SELECT, INSERT, UPDATE` on `customer` (no `DELETE`, `TRUNCATE`: the service never deletes a customer); `SELECT, INSERT` on `credit_movement` (the idempotency journal is append-only); `SELECT, INSERT, UPDATE` on `credit_reservation` (rows are never removed); `SELECT, INSERT, UPDATE, DELETE` on `outbox_event` (the relay marks, parks and purges rows); `SELECT` on `flyway_schema_history` (the service validates at startup). Not the owner, so it cannot `ALTER`/`DROP` the tables nor record a migration (`CustomerServiceIT.theRuntimeRoleCanOnlyDoWhatTheServiceDoes`) |
| backfill role (the owner, or a DBA) | `db/backfill/run-backfill.sh`, step 3a, `db/fixtures/parity_seed_customers.sql` | `CREATE` on the database (staging schema `backfill_stage`); `SELECT, INSERT, UPDATE` on `customer` and `credit_reservation`; the parity seed deletes rows (`customer`, `credit_movement`, `credit_reservation`), which the runtime role may not |

The owner's credential is mounted only by the migration Job, and the hook deletes it from the namespace after a successful run ([decision 0001](../architecture/decisions/0001-flyway-runs-in-a-migration-job.md)). The guarantee therefore holds once the DBA bootstrap has created separate roles in the environment. A DBA (or the RDS master user) can always change rows; tamper evidence against them is not built.

DBA bootstrap, per environment, before the first deploy: run [`db/bootstrap/roles.sql`](../../db/bootstrap/roles.sql) with psql against `db_cus_profile_kyc_<env>` as the RDS master user (`psql -X -v ON_ERROR_STOP=1 -d db_cus_profile_kyc_<env> -f db/bootstrap/roles.sql`). It is idempotent and creates or checks both `LOGIN` roles: the owner `customer_profile_owner` (owns `sc_cus_profile_kyc`, `CONNECT` and `CREATE` on the database) and the runtime role `customer_profile_app` (`CONNECT` only; V13 grants the rest). Neither gets an elevated attribute, and the run stops if one already has one or if the runtime role is a member of the owner. The file sets no password: set each one out of band with psql `\password <role>` (it prompts and sends only a SCRAM hash), write the two `{"username","password"}` pairs to the secrets Terraform creates, and set Helm `externalSecret.migrationSecretName` (the chart does not render without it). The migration Job then migrates as the owner, and V13 grants the runtime role. The terraform-modules `aurora-postgresql` module is to ship only a generic `role_bootstrap_sql` output (proposed; not on that repository's `main` when this was written), which cannot know this schema or V13's split; this repo's file is the one to run. `scripts/migration/verify-role-bootstrap.sh` rehearses it on a scratch PostgreSQL in CI (`deploy/data-split-rehearsal`): two runs as a non-superuser DBA role, the migrations as the owner, a third run, then the roles, owner and V13 grants.

**Local and ephemeral environments must run `migrate` before the application starts.** The service only validates the schema, so an empty or unmigrated database does not start it. Local single-user runs (`./gradlew bootRun`, no `DB_MIGRATION_*`) have no Job: run `./gradlew :customer-bootstrap:bootRun --args=migrate` (or `java -jar customer-profile-kyc-service.jar migrate`) first, then the application without the argument. V13 then changes nothing (the runtime role is the connecting user). CI follows the same rule: the integration tests migrate as the owner in their test context, and the backfill rehearsal applies the migrations with psql as the schema owner; nothing boots the jar against an unmigrated database.

### Database migrations (Flyway Job)

Flyway never runs in the service pods ([decision 0001](../architecture/decisions/0001-flyway-runs-in-a-migration-job.md)). The chart's `templates/migration-job.yaml` is a Helm `pre-install` and `pre-upgrade` hook Job. It runs the service image with the argument `migrate` (`DatabaseMigration`: datasource, Flyway and `DatabaseTlsGuard` only), migrates as the schema owner through the verified `DB_URL`, and exits 0, or 1 on any failure. Its pods are labelled `app.kubernetes.io/name=customer-profile-kyc-service` (the mesh's Aurora egress) and `app.kubernetes.io/component=db-migration`, which no Service, PDB or topology spread selects (cicd-templates 335a345), and `sidecar.istio.io/inject: "false"`, written after `podLabels` (whose own inject key is omitted) so it overrides them: Aurora egress is a Kubernetes NetworkPolicy on the name label, so the Job needs no proxy, and without native sidecars an injected proxy would keep the Job from completing. The service pods have only the runtime role. At startup they validate the schema history and refuse to start while a migration is pending (`FlywayValidateException` in the log).

Deploy order, on `helm install` and on every `helm upgrade`:

1. Hooks at weight -10: the `customer-profile-kyc-service-db-migration` ExternalSecret (External Secrets syncs the owner credential into Secret `customer-profile-kyc-service-db-migration`) and the Job's ServiceAccount of the same name (no IAM role, no API token).
2. Hook at weight 0: Job `customer-profile-kyc-service-db-migration`. Its pod waits in `CreateContainerConfigError` until the Secret exists, then migrates. `backoffLimit` 1, `activeDeadlineSeconds` 600, `ttlSecondsAfterFinished` 86400 (values `migrationJob`).
3. Only after the Job succeeds: Helm deletes the hook ExternalSecret and ServiceAccount (the Secret goes with them), then creates or updates the regular resources (ConfigMap, Deployment and the others). New pods validate and start.

Use `helm upgrade --install ... --timeout 15m`. Helm's default 5 min is shorter than the Job's 600 s deadline plus the rollout, so Helm would give up while the Job is still running.

Deploy pipeline: this repository's CI is hand-rolled (`Deployability` renders and validates the chart; nothing deploys from here) and no workflow calls the cicd-templates `helm-deploy.yml` yet. When the service adopts it, the calling job passes `helm-timeout: 15m` to `helm-deploy.yml` (the same 15 min as above, so the hook Job's 600 s deadline fits) and sets its own `timeout-minutes` above that, 20 for example, so the GitHub job does not cancel Helm while the migration Job is still within its deadline.

A failed Job blocks the rollout. Helm marks the install or upgrade failed and does not touch the Deployment. On an upgrade the old pods keep serving on the old schema; on a first install nothing is deployed. Flyway applies each migration in its own transaction, so a failed migration leaves the schema at the last applied version and marks nothing as applied. To diagnose:

```bash
kubectl -n customer get job,pod -l app.kubernetes.io/component=db-migration
kubectl -n customer logs job/customer-profile-kyc-service-db-migration --all-containers
kubectl -n customer describe job customer-profile-kyc-service-db-migration   # DeadlineExceeded, BackoffLimitExceeded
kubectl -n customer get externalsecret customer-profile-kyc-service-db-migration   # SecretSynced?
```

Common causes: the owner secret is not filled or not synced (the pod stays in `CreateContainerConfigError` until the deadline), `rds-ca-bundle` is missing (`ContainerCreating`), Aurora egress is not granted, `DatabaseTlsGuard` refused the URL, or a migration failed (SQL error in the log). A pod stuck `Running` with the migration finished means a sidecar was injected after all: check the pod's labels.

To re-run, fix the cause and run the same `helm upgrade` again. The `before-hook-creation` policy deletes the old Job, ExternalSecret and ServiceAccount and creates new ones. The ExternalSecret and the owner Secret stay in the namespace after a failed run, until that re-run or until you delete them (`kubectl -n customer delete externalsecret customer-profile-kyc-service-db-migration`). Never repair by giving the service pods the owner credential. If the history needs `flyway repair` (a changed checksum, or a migration that failed outside a transaction), the DBA runs it as the owner after review, outside the cluster.

`helm rollback` runs no hook. An older image validates against a newer history: Flyway ignores applied migrations it does not know. A rollback therefore starts as long as the newer migrations were additive. A migration that an older image cannot run on is a release decision: write it expand/contract style.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<customer service conninfo>" USD`

The currency is a required argument with no default (the script prints its usage and exits 2 without it). Decision in force: the ledger currency is USD, which the monolith evidence points to. `svc-ln-loan-lifecycle`'s `loan.customer-credit.ledger-currency` must be the same value before step 5.

1. Exports `customers` in one read-only snapshot.
2. Stages them in `backfill_stage` in the service database and transforms them (`02_transform_into_customer_service.sql`; the mapping is listed at the top of that file). The monolith stored no currency, e-mail, phone, score or income: currency comes from the run parameter and the rest stay empty.
3. Reconciles (`03_reconcile.sql`): the staged totals (rows and `sum(credit_limit)`) must equal the monolith totals taken in the same snapshot as the export, every staged customer must exist in the service, every customer the service has not changed must equal its monolith row (name, surname, `credit_limit`, version), no customer may have been changed by both the service and the monolith, no migrated customer may have `used_credit > credit_limit`, and every migrated customer's `used_credit` must equal the sum of its open `credit_reservation` amounts (`reserved_amount - released_amount`; both are 0 before step 3a and equal after it). Any problem fails the run and names the customer.

The backfill connects as the schema owner (or a DBA), never as the runtime role: it creates `backfill_stage` and writes `customer` rows the service may have changed (section 1, "Database roles").

**Decision: `used_credit` is never copied from the monolith.** `02_transform_into_customer_service.sql` writes `used_credit = 0` on the first copy and a re-run never touches it (`run-backfill.sh` does not even export `customers.used_credit_limit`). That column is **not** the monolith's credit position: the live monolith moves credit through `CustomerCreditServiceAdapter` (`loan-context/loan-infrastructure`), an in-memory map that never writes the column, so it still holds the seed value (`V5__Insert_sample_customers.sql`). The position the service must start from is the set of loans the monolith holds credit for, which step 3a rebuilds as `credit_reservation` rows and from which it sets `used_credit`; step 3a is the only source of `used_credit`, and a backfill re-run after it keeps what it set. `03_reconcile.sql` therefore compares neither `used_credit` nor its total with the monolith; it checks instead that every migrated customer's `used_credit` equals the sum of its open reservations, which holds both before step 3a (0 = 0) and after it. The loan backfill reserves no credit either. The customer backfill does not depend on the loan or payment backfills and can run first.

Re-runs are expected until cut-over. The monolith keeps reserving and releasing credit until the write freeze (step 2), so each re-run refreshes every migrated customer the service has not changed yet (`version = legacy_synced_version`, the service's own JPA version) and never overwrites one it has. Monolith changes are detected from the data, not from `customers.version`, which the monolith never bumps (its `CustomerEntity` has no `@Version`): each copy stores `legacy_synced_hash`, the md5 of the monolith's `name|surname|credit_limit` (`backfill_stage.synced_hash`; V6 introduced the column with `used_credit_limit` as a fourth input, dropped with the decision above, so a monolith movement of `used_credit_limit` is neither copied nor reported as a two-sided change). A customer the service has changed whose monolith row no longer matches that fingerprint was changed on both sides, which means two credit ledgers; reconciliation reports it as `monolith changed it after the service did`, and it must be resolved by hand before the cut-over goes on. `scripts/migration/verify-backfill.sh` rehearses all of this on a scratch PostgreSQL (first load with non-zero monolith `used_credit_limit` values landing as `used_credit = 0`, idempotent re-run, a monolith `credit_limit` change without a version bump picked up, a monolith `used_credit_limit` movement ignored, a step 3a rehearsal whose `used_credit` survives a re-run, a `used_credit` that differs from the open reservations refused, a service change kept, a two-sided change refused both without and with a monolith version bump) and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

Customer data must have one writer at any time: the monolith's `public.customers` until step 2, nobody during the freeze (steps 2 to 4), this service from step 5 on. The order is freeze, final backfill, reconcile, then route writes; the service never takes a write on a row the final backfill has not refreshed.

**Deployment prerequisites** (before step 1, in every environment):

- DBA bootstrap of both database roles done with `db/bootstrap/roles.sql` and their passwords and secrets filled (section 1, "Database roles"): the owner `customer_profile_owner` in `<env>/customer-profile-kyc-service/db-migration`, the runtime role `customer_profile_app` in `.../db-app`; Helm `externalSecret.migrationSecretName` set to the Terraform output `migration_db_secret_name` (the chart refuses to render without it). Deploy with `helm upgrade --install ... --timeout 15m`: the migration Job runs first and must succeed before any pod starts (section 1, "Database migrations (Flyway Job)"); Helm's default 5 min is shorter than the Job's 600 s deadline plus the rollout.
- cert-manager, trust-manager and the mesh repo's trust-manager `Bundle` `rds-ca-bundle` are installed before this chart. The Bundle publishes ConfigMap `rds-ca-bundle` (key `global-bundle.pem`) in namespace `customer` (cicd-templates 4f0f266). The chart mounts it read-only at `/etc/fintechbankx/rds-ca`, not optional, and exports `DB_SSL_ROOT_CERT`; until the ConfigMap exists the pods stay in `ContainerCreating` (event `configmap "rds-ca-bundle" not found`). Check first: `kubectl -n customer get configmap rds-ca-bundle -o jsonpath='{.data.global-bundle\.pem}' | head -1` prints `-----BEGIN CERTIFICATE-----`.
- Deployment selector change (local commit d35fbfe, `app.kubernetes.io/component: service` added to the pod selector): `spec.selector` of a Deployment is immutable, so `helm upgrade` of a release installed before that change fails with `field is immutable`. Nothing is installed today. If a release exists: `kubectl -n customer delete deployment customer-profile-kyc-service` (or `helm -n customer uninstall <release>`), then `helm upgrade --install` again. The delete takes the pods down until the new ones are ready, so do it in a maintenance window, before step 5 routes traffic here. The Service and PDB selectors are mutable and update in place.
- `config.DB_URL` is the Terraform output `jdbc_url`: `...?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`. Every values route into a pod's environment goes through the platform guard `fbx.guard` (`templates/_fbx_helpers.tpl`, copied unchanged from fintechbankx-platform-delivery-iac-cicd-templates a4f0072, sha256 checked by the `deploy/helm` job; re-copy it when the platform guard changes), called at the top of `deployment.yaml` and of `migration-job.yaml`, then through this chart's stricter `customer.validate` (`templates/_helpers.tpl`). Rendering fails with the key and the reason; nothing is rewritten. What is refused:
  - **DB_URL**: anything but a `jdbc:postgresql:` URL whose query (parsed the way PgJDBC reads it) has exactly one `sslmode=verify-full` and exactly one `sslrootcert` equal to the mounted bundle, both in lower case (the driver ignores `SSLMODE=verify-full` and would fall back to `prefer`); any `sslfactory`, `sslfactoryarg`, `sslhostnameverifier`, `sslpasswordcallback` or `service` parameter in any case (a `pg_service.conf` entry can replace the host and the ssl settings); a TLS key before the `?`, a percent-encoded name, `=` or `&`; `${` or `$(` anywhere (resolved after the check, either could append `sslmode=disable`); `DB_URL` under another spelling (`db.url`, `dbUrl`).
  - **Config key names**, matched as given and in every spelling Spring's relaxed binding accepts (case, dash, dot, underscore, index, such as `spring.config.import[0]`, `SPRING-DATASOURCE-URL`, `spring.pro-files.active`): `SPRING_DATASOURCE_*` (including `USERNAME` and `PASSWORD`: the runtime role is `DB_USERNAME`, its password comes from the ExternalSecret), `SPRING_FLYWAY_*`, `SPRING_LIQUIBASE_*`, `SPRING_R2DBC_*`, any `*JDBC_URL`, `SPRING_APPLICATION_JSON`; every `SPRING_CONFIG_*` (import, location, additional location, name, `activate.*`; the chart renders no config import, and a configtree would be allowed only as a chart-rendered value on the fixed mount `optional:configtree:/etc/fintechbankx/config/`); every `SPRING_PROFILES_*` (active, include, default, group; `kafka.runtime` renders the only profile); `SPRING_SSL_*` and any SSL bundle name (a bundle can replace the trust anchor); any name with an `ssl-root-cert`, `ssl-mode`, `ssl-factory`, `ssl-host-name-verifier` or `ssl-password-callback` part, which covers `DB_SSL_ROOT_CERT` in any spelling, empty or not (the chart sets it from the mounted bundle; it is the only switch of the TLS startup assertion), and `PGSSLROOTCERT`; `FINTECHBANKX_TLS_*` (the services' assertion off switch); `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS` whatever they hold (JVM options are fixed by the image); `LOGGING_LEVEL_*` (`LOGGING_LEVEL_ORG_POSTGRESQL=TRACE` would write the wire protocol, with row data, to the pod log). A key must be a ConfigMap key (`[-._a-zA-Z0-9]+`), so a newline cannot open another key.
  - **Kafka**: a `*security.protocol` key other than `SASL_SSL` with `kafka.runtime: msk` or `SSL` with `strimzi`; an `*endpoint.identification.algorithm` key other than `https`; `kafka.runtime` other than `msk` (default) or `strimzi`; `strimzi` without `kafkaTls.secretName`.
  - **Other routes**: `envFrom`, `extraEnvFrom` and `externalSecret.dataFrom` (they load names nobody checks), and `extraEnv`, `env` and `javaToolOptions` (this chart renders none of them; a value that is silently ignored is a value someone believes applied); a control character in `externalSecret.remoteSecretName`, `oidcClientSecretName` or `migrationSecretName`; a `serviceAccount.name` that is not a DNS-1123 label; `$(` in an env value the chart renders (the Job's `DB_URL` and `DB_USERNAME`: Kubernetes would expand it from the db-migration secret's keys).
  - The templates quote every key and value they write (ConfigMap, ExternalSecret, pod spec) and render numbers as integers.

  **Spring profile (changed in this chart version).** `SPRING_PROFILES_ACTIVE` is no longer a `config` key: set `kafka.runtime` (`msk` renders `kafka-msk`, the default; `strimzi` renders `kafka-strimzi` and needs `kafkaTls.secretName`). The chart renders it as an env entry on the Deployment; a values file that still sets `config.SPRING_PROFILES_ACTIVE` fails to render (`config.SPRING_PROFILES_ACTIVE is not allowed`). Nothing is installed today; drop the key from any environment values file before the first install.

  At startup `DatabaseTlsGuard` checks the same with the PostgreSQL driver's own URL parser (`spring.datasource.url`, and `spring.flyway.url` and `spring.datasource.hikari.jdbc-url` when set, which must also equal `spring.datasource.url`: a second URL that verifies but names another server is refused too) against `DB_SSL_ROOT_CERT`, and the pod fails to start with a message naming the property (never the URL); it is skipped only when `DB_SSL_ROOT_CERT` is unset, which the chart never allows.

Owners: **monolith squad** (enterprise-loan-management-system flags and anti-corruption client), **customer squad** (this service), **lending squad** (`svc-ln-loan-lifecycle`), **migration lead** (runs the backfill and signs off reconciliation), **change manager** (go/no-go and the freeze window). Every step needs the migration lead's sign-off in the change record before the next one starts.

| Step | Action | Owner | Validation (all must hold) | Rollback trigger (any one) | Rollback |
|---|---|---|---|---|---|
| 1 | Deploy the service with `helm upgrade --install ... --timeout 15m` and the outbox relay off (the chart default, `config.OUTBOX_RELAY_ENABLED: "false"`); the migration Job migrates `sc_cus_profile_kyc` as the owner before any pod starts, and the pods validate as the runtime role; run the backfill as the owner and re-run it until it reconciles. Loan keeps using the monolith for credit; nothing routes to this service yet | customer squad | `run-backfill.sh ... USD` exits 0; staged totals equal monolith totals; readiness `UP` on every replica | two consecutive backfill runs fail reconciliation | drop `sc_cus_profile_kyc`; nothing else changed |
| 2 | **Full monolith write freeze on customers**: the monolith flag refuses credit writes (reserve, release, limit changes), customer creation and profile edits with a retryable error; reads continue from the monolith. Loan is told to stop originating for the window | monolith squad, change manager | no write reaches `public.customers`: `SELECT count(*), max(updated_at) FROM customers` is unchanged across two reads 5 minutes apart; the flag's refused-write counter is the only write path that moves | the freeze has lasted 60 minutes without reaching step 5, or refused writes exceed the window agreed with the business | lift the freeze; the monolith stays the writer and nothing moved here |
| 3 | Final backfill under the freeze: `run-backfill.sh ... USD` | migration lead | exits 0; staged totals equal the monolith totals of the same snapshot; zero `monolith changed it after the service did`; zero `missing in the service` | any non-zero exit or any reported customer | lift the freeze (as step 2); fix the cause, start again from step 2 |
| 3a | **Backfill credit reservations from the monolith's open loans** (to be rehearsed by the customer squad with the lending squad before step 2 is scheduled): under the freeze, insert one `sc_cus_profile_kyc.credit_reservation` row per monolith loan that is **disbursed and not fully paid**: `customer_id` = `loans.customer_id` as text, `reference` = `loans.id` exactly (confirmed by the lending squad: loan ids are the monolith's `loans.id` carried over unchanged, and `svc-ln-loan-lifecycle`'s client `CustomerProfileHttpAdapter`, selected with `CUSTOMER_CREDIT_ADAPTER=http`, sends the loan id as the body `reference` on every reserve and release call, so its referenced releases find the row), `reserved_amount` = `loans.loan_amount` exactly (the principal: in the monolith only disbursement reserves credit, at the principal, `LoanManagementService.java:104`, and only the payment that fully pays the loan releases it, `:120-127`, so a partially paid loan still holds its whole principal; the loan service's full-payoff release names that reference for the whole principal, so a row backfilled with less would answer 422 `RELEASE_EXCEEDS_RESERVATION` and leave the loan waiting for an operator), `released_amount` = 0, currency = the run's ledger currency given by the operator (the monolith stores none). `public.loans` (V2) has `loan_amount` and `is_paid` but no disbursement or status column, so the selection is `WHERE NOT is_paid`; that over-includes loans created but never disbursed, for which the monolith never reserved credit: the lending squad lists those from its own records and they are left out before reconciliation. Then set each migrated customer's `used_credit` to the sum of its open reservations (step 3 left it at 0). **Never copy `customers.used_credit_limit`**: the live monolith's `CustomerCreditServiceAdapter` is an in-memory map that never writes that column, so it holds the seed value, not a credit position; the transform does not copy it either, so this step is the only source of `used_credit` (section 2). Re-runnable (upsert on customer id + reference; a backfill re-run never resets `used_credit`) and recorded in the change record | customer squad, lending squad, migration lead | the number of rows equals the monolith's count of `NOT is_paid` loans minus the never-disbursed list, and `sum(reserved_amount)` equals `sum(loan_amount)` of the same loans, by count and money total; per row, `reserved_amount` equals `loans.loan_amount` of the loan named by `reference` and `released_amount` is 0 (checked here against the monolith's `loans`, which `03_reconcile.sql` cannot see); per customer, `used_credit` equals the sum of its open reservations (`reserved_amount - released_amount`) and is at most `credit_limit` (`03_reconcile.sql` reports `used_credit differs from its open reservations` otherwise); 20 random open loans: a `GET` of the reservation by customer and loan id matches the monolith | any count or money total that differs, any customer whose open reservations exceed `credit_limit`, or a never-disbursed loan left in | delete the inserted `credit_reservation` rows and reset `used_credit` to 0, the value step 3 left; lift the freeze (as step 2) |
| 4 | Reconcile and verify: re-run `03_reconcile.sql` alone and the parity checks against the frozen monolith | migration lead, customer squad | zero problem rows (`03_reconcile.sql` checks per customer that `used_credit` equals its open reservations); `count(*)` and `sum(credit_limit)` of migrated rows equal the monolith's and `sum(used_credit)` equals the open-reservation total of step 3a (the monolith's `used_credit_limit` is never copied or compared, section 2); zero rows with `used_credit > credit_limit` on either side; 20 random customers read through `GET /api/v1/customers/{id}/credit` equal their monolith rows | any discrepancy | lift the freeze (as step 2) |
| 5 | Route writes to the service and lift the freeze: the monolith flag sends credit writes, customer creation and profile edits through the anti-corruption client to this API, and reads too; switch `svc-ln-loan-lifecycle` to this service (`CUSTOMER_CREDIT_ADAPTER=http`, client-credentials token). This step is the precondition for loan runbook step 2 | monolith squad, lending squad | for 30 minutes: 5xx below 0.5 % of `http_server_requests_seconds_count{uri=~"/api/v1/customers.*"}`; p99 below 800 ms; `max(updated_at)` of `public.customers` unchanged (no write bypasses the flag); zero customers with `used_credit > credit_limit` | 5xx at or above 0.5 % for 5 minutes, any write landing in `public.customers`, any customer over their limit, or any 5xx on credit reserve/release that the loan service cannot replay | freeze again; copy every row changed here since step 5 (`version <> legacy_synced_version`, plus customers created here) back to `public.customers` (reverse sync, to be written and rehearsed before step 5 is scheduled); then route writes back to the monolith. Flipping the flag back without the reverse sync loses every write made here |
| 6 | Merge asyncapi-catalog PR #9 and create the topic `evt.cus.customer.v1` (fintechbankx-platform-event-streaming-kafka; the per-event topics are no longer used, ADR-019 s8); once mesh #11's MSK egress is applied, enable the outbox relay with `helm upgrade ... --reuse-values --set config.OUTBOX_RELAY_ENABLED=true`; consumers move to `evt.cus.customer.v1` and filter on the `eventType` header (for example the payments KYC consumer) | customer squad, event-streaming squad | `outbox_pending_events` drains to below 100 and `outbox_oldest_pending_age_seconds` to below 60 within 10 minutes; `outbox_parked_rows` is 0 | `outbox_parked_rows` above 0, or pending still growing after 10 minutes | relay off; events stay in the outbox (nothing lost) |
| 7 | Remove the monolith's dead customer write code and the flag | monolith squad | monolith regression suite green; no write path to `public.customers` left in `src/main` | monolith regression failures | revert the removal; the flag still routes to this service |
| 8 | After the loan and payment cut-overs are complete and one full month-end cycle has passed: drop the monolith foreign keys that reference `customers` (`loans.customer_id` and any other), then drop `customers` | monolith squad, migration lead | month-end figures from this service equal the finance report | any month-end discrepancy before the drop | restore from snapshot |

**Dependency on the loan extraction.** Step 2 of the loan runbook (`svc-ln-loan-lifecycle` taking traffic with its HTTP customer adapter) has the precondition **step 5 here is done**: the freeze, final backfill and reconciliation have passed and the monolith moves credit only through this service. Before that the monolith still reserves and releases credit in `customers.used_credit_limit` while the loan service would use this service's `customer.used_credit`: two credit ledgers for the same customer, which can overdraw the limit and drift apart.

**Deletion order for step 8.** In the monolith, `customers` is referenced by `fk_loans_customer` (`loans.customer_id`, V2), `fk_loan_applications_customer` (V12), `fk_credit_reports_customer` (V14) and `fk_risk_assessments_customer` (V15), all `ON DELETE RESTRICT`. `payments` references `loans` (`fk_payments_loan`, V4), not `customers`. So the table goes last:

1. Payment extraction finishes and drops `fk_payments_loan` (or `payments` itself), so the loan tables can change.
2. Loan extraction drops `fk_loans_customer` and `fk_loan_applications_customer` (or the loan tables with its own final step).
3. The risk extraction drops `fk_credit_reports_customer` and `fk_risk_assessments_customer` (or those tables).
4. Only then: step 8 here, drop `customers`. Before 2 and 3 a plain `DROP TABLE customers` fails on the foreign keys; never use `DROP ... CASCADE`, which would silently remove the other contexts' constraints.

## 4. Parked outbox events

Relay failures are classified by ADR-021 decision 4 (adr-runbooks, `ADR-021-database-per-service-and-data-migration.md`):

- **Payload errors** that can never succeed for that row (`RecordTooLargeException`, `SerializationException`,
  `InvalidTopicException`): the relay parks the row (`parked_at` set, reason in `last_error`, counted once by
  `outbox_parked_events_total`) and continues with the next one.
- **Everything else**, including retriable errors and timeouts, authorization errors (`TopicAuthorizationException`,
  SASL/IAM failures) and any unclassified exception: the relay stops the batch without marking the row or anything
  after it, retries with backoff and alerts (`outbox_oldest_pending_age_seconds`). It never skips or parks a row for
  such an error, so per-customer ordering and the complete event history are kept. There is no time ceiling: a row
  stuck this way holds the batch until the cause is fixed or an operator parks it by hand (below).

Parked rows are skipped and never purged. The same customer's later events wait behind a parked row (they stay in
`outbox_pending_events`), so a consumer never sees a customer's events out of order; other customers' events keep
flowing. Metrics (all tagged `service="svc-cus-profile-kyc"`):

- `outbox_parked_events_total{exception="<simple class name>|OperatorPark"}`: counter, incremented once per parked
  row. A relay park is tagged with the root cause and marked `park_counted` in the same update; a manual park is counted
  once by the relay on its next run, tagged `OperatorPark` (V9 column `park_counted`). Any increase alerts (below),
  because consumers are missing that customer's
  events until the replay.
- `outbox_oldest_pending_age_seconds`: age of the oldest row waiting for the relay, from its `created_at` (0 when
  none). This is the alert signal (ADR-021 decision 4).
- `outbox_pending_events`: rows waiting for the relay.
- `outbox_send_failures_total{exception="<simple class name>"}`: failed sends by exception class (no ids or topics);
  use it to tell an authorization failure (`TopicAuthorizationException`, `SaslAuthenticationException`) from an
  outage (`NetworkException`, `TimeoutException`). A non-payload failure is not written to the row (`last_error`
  stays empty); the relay log has the event id and the error.

Outbox alerts: shipped in platform observability (fintechbankx-platform-observability-sre-operations) PR #11 at
commit `eca7aa0` (not merged yet). That PR owns the rules; this chart ships no PrometheusRule and this service ships no
alert rule. The rules key on `service_id`, which comes from the pod label `fintechbankx.io/service-id:
svc-cus-profile-kyc` (set in the chart and checked by the deploy/helm CI job), and route by squad (owning squad
**customer**):

- **OutboxRelayStalled**: `max(outbox_oldest_pending_age_seconds{service_id="svc-cus-profile-kyc"}) > 900` for 5m,
  severity critical;
- **OutboxSendFailures**: any increase of `outbox_send_failures_total{service_id="svc-cus-profile-kyc"}` over 10m,
  severity warning;
- **OutboxEventsParked**: any increase of `outbox_parked_events_total{service_id="svc-cus-profile-kyc"}` over 15m, no
  `for` clause, severity warning. Operator parks (`OperatorPark`) also fire it.

The same PR widens the AMP remote-write keep regex to the `outbox_` series, so they reach the alerting backend once it
merges. Scraping relies on the pod annotations
`prometheus.io/scrape`, `prometheus.io/port` and `prometheus.io/path` (the platform PodMonitor reads them); the
deploy/helm CI job checks they are rendered. Until PR #11 merges, the customer squad watches these series on its
dashboards.

`outbox_parked_rows`: gauge of the rows currently parked (for dashboards and the replay check).

Every meter carries `service="svc-cus-profile-kyc"`, `app` (`METRICS_APP`, the chart's service account
`customer-profile-kyc-service`) and `squad` (`METRICS_SQUAD`, `customer`); the chart sets both.

After a stopped batch the relay backs off: it waits the poll interval (`customer.outbox.relay.interval`, 1 s), doubling
per stopped batch up to `customer.outbox.relay.backoff-max` (`OUTBOX_RELAY_BACKOFF_MAX`, default `PT5M`), and resets
after a completed batch. The backoff is per replica and in memory; a restart starts from the poll interval again.
Only the replica holding the advisory lock sends, but each replica keeps its own backoff, so while a failure lasts the
cluster tries up to N times per backoff period for N replicas (at the 5 minute cap and 3 replicas, about one attempt
every 100 s). Sharing the backoff state across replicas is a possible follow-up.

Manual park (operator only). The relay never parks a row for a non-payload error. When one row holds the batch on
such an error (`last_error` stays empty; see the relay log and `outbox_send_failures_total`) and the cause cannot be
fixed soon, an operator may park that row so the other customers' events flow; that customer's later events wait
behind it. It needs the incident or change ticket in the reason, and the replay below once the cause is fixed:

```sql
-- Manual park (operator only): the oldest row waiting for the relay, held by a non-payload error.
UPDATE sc_cus_profile_kyc.outbox_event
SET parked_at = now(), last_error = left('manual: <reason>', 512)
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NULL;
```

Un-park (replay), after fixing the cause (topic created, IAM policy fixed, payload size limit raised):

```sql
-- Inspect
SELECT event_id, created_seq, aggregate_id, topic, attempts, last_error, parked_at
FROM sc_cus_profile_kyc.outbox_event
WHERE published_at IS NULL AND parked_at IS NOT NULL
ORDER BY created_seq;

-- Un-park one row (or drop the event_id filter to replay all; the relay sends them in created_seq order).
-- park_counted = FALSE lets a later park be counted again; first_failed_at is no longer written (ADR-021),
-- clearing it tidies rows parked before that change.
UPDATE sc_cus_profile_kyc.outbox_event
SET parked_at = NULL, park_counted = FALSE, first_failed_at = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```

The relay publishes the un-parked row, then the customer's held events, on its next run
(`customer.outbox.relay.interval`, 1 s). Consumers de-duplicate on `eventId`, so replaying a row that Kafka did in fact
accept is safe. If an event must never be sent (for example a payload that cannot be fixed), do not delete it: set
`published_at = now()` and `last_error = 'discarded: <ticket>'` with the data owner's approval, which releases the
customer's held events. Record each replay or discard (event ids, cause, operator) in the change log of the environment.

## 5. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Flyway runs only in the chart's pre-install/pre-upgrade Job as the schema owner; the pods hold the runtime role V13 grants and only validate the schema (decision 0001; `deploy/helm` migration Job check; `DatabaseMigrationIT`, `CustomerServiceIT.theRuntimeRoleCanOnlyDoWhatTheServiceDoes`)
- [ ] DBA bootstrap per environment: roles `customer_profile_owner` and `customer_profile_app` created, both secrets filled, `externalSecret.migrationSecretName` set
- [ ] Migration Job run once on a cluster (External Secrets sync timing of the hook ExternalSecret; it is verified by rendering and mutation checks only)
- [x] Events written through a transactional outbox, relayed in order with one active relay; no personal data in event payloads
- [x] Credit reserve and release are idempotent on this side: `x-idempotency-key` is required and unique per customer in the database
- [ ] Idempotent end to end: `svc-ln-loan-lifecycle` derives the key from the loan id so its retries resend it (loan PR)
- [x] Optimistic locking on the customer, so concurrent reservations cannot overdraw credit
- [x] A release takes at most its reservation (by `reference`); a release whose `reference` matches no reservation is always 422 `RESERVATION_NOT_FOUND`; only a release with no reference takes untracked used credit; concurrent releases cannot exceed either (`CreditConcurrencyIT`, `CreditReservationIT`)
- [ ] Step 3a written and rehearsed: `credit_reservation` rows backfilled from the monolith's open loans (reference = loan id), reconciled by count and money total
- [x] Backfill rehearsed with reconciliation in CI, including re-runs before cut-over
- [x] Service callers get the credit position only; the full record (name, e-mail, phone, income, score) is for staff and the customer
- [x] Tokens must name this service in `aud`; service calls must come from a client on `SERVICE_CALLERS` (credit) or `SERVICE_CALLERS_KYC` (KYC status read)
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] `svc-ln-loan-lifecycle` calls with a client-credentials token (`SERVICE` role) instead of forwarding the end user's token
- [ ] Keycloak: audience mapper for `svc-cus-profile-kyc` on each calling client (fintechbankx-platform-identity-iam-keycloak-ldap)
- [ ] Keycloak: the service client `svc-cus-profile-kyc` holds the scoped FGAP v2 permission (view and manage users in group `/customers` only, identity repo 8f9024b), not realm-management `manage-users`; a user outside `/customers` answers 403, which the identity link reports as 422 `IDENTITY_USER_NOT_FOUND`
- [ ] After enabling the identity link (`IDENTITY_ADMIN_ENABLED=true`): if every link returns 422, check the FGAP permission first; `identity_directory_responses_total{status="403"}` rising with every attempt and the warning "Keycloak answered 403 reading an identity user" in the log mean the client is not scoped to `/customers`
- [ ] Identity link write scope: the `PUT /admin/realms/fintechbankx/users/{id}` body is exactly `username`, `email`, `firstName`, `lastName`, `emailVerified` (each the stored value from the preceding `GET`) and `attributes` (the stored map plus `customer_id`); never `enabled`, credentials, `requiredActions`, federated identities, roles, groups or `access` (platform ruling, option a; key set from observability #11 e06915f; `KeycloakIdentityDirectoryAdapterTest`; drilled on Keycloak 26.7.5 with identity #11's user profile: enabled, requiredActions, email and names unchanged, `customer_id` set)
- [ ] Residual risk recorded in the change record: Email or name changes made to a customer user through `svc-cus-profile-kyc` (its FGAP v2 `manage-members` permission allows them, although this service only sets `customer_id`) are **accepted residual risk, owned by the customer squad**, until the identity-owned link service exists (decision 17). Observability's `KeycloakServiceAccountAdminEventOutOfScope` (observability #11 e06915f) still alerts on any admin event by this service account with `enabled=false`, credentials, reset-password, execute-actions-email, `requiredActions`, federated identities, role or group mappings, a `DELETE`, or anything outside `/customers`.
- [ ] Namespace `customer` onboarded to the mesh (istio-injection) by the mesh-security squad
- [ ] Monolith anti-corruption client behind a flag, with a freeze mode that refuses customer creation, profile edits and credit writes (enterprise-loan-management-system)
- [ ] Reverse sync (service rows changed since step 5 back to `public.customers`) written and rehearsed, so step 5 can be rolled back without losing writes
- [ ] Topic `evt.cus.customer.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka), and the per-event `evt.cus.customer.<event>.v1` topics removed from its provisioning script (ADR-019 s8); unsent outbox rows from before Flyway V11 already point at the aggregate topic
- [ ] KYC verification (documents, screening) is not in the monolith slice and is not built here yet
- [ ] Production backfill and reconciliation report attached here
