# RUNBOOK-EXTRACT-cus-profile-kyc

Extraction of the Customer aggregate from `enterprise-loan-management-system`
into `svc-cus-profile-kyc` (this repository), following the strangler-fig
steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cus` / `svc-cus-profile-kyc` |
| Slice | Customer aggregate: profile, contact details, credit limit and credit reservations |
| Owned data | `db_cus_profile_kyc_<env>`, schema `sc_cus_profile_kyc`: `customer`, `credit_movement`, `credit_reservation`, `outbox_event` |
| Events | `evt.cus.customer.{created,contact-updated,credit-limit-updated,credit-reserved,credit-released,credit-score-updated}.v1` (contract in this repo, `api/asyncapi/svc-cus-profile-kyc.yaml`; proposed to the catalog in fintechbankx-governance-api-contracts-asyncapi-catalog PR #9, which must merge before the relay is switched on) |
| Called by | `svc-ln-loan-lifecycle`: `GET /api/v1/customers/{id}/credit` (no personal data), `POST .../credit/reserve` and `.../credit/release` with a required `x-idempotency-key` and the loan id as `reference` |
| Caller identity | Client-credentials token with the `SERVICE` realm role, `aud` containing `svc-cus-profile-kyc` (Keycloak audience mapper) and `azp` on `SERVICE_CALLERS` (credit) or `SERVICE_CALLERS_KYC` (KYC status read) |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `customers` (V1) | this service, `customer` | id kept as text (`'42'`), which loans and payments already reference; numeric id kept in `legacy_customer_id` |
| `update_customers_updated_at` trigger | removed | `Customer` keeps `updated_at` |
| `customer_management` schema (V1__Create_customer_management_schema) | not migrated | parallel schema never used by the running application; confirm it has no rows before the cutover |
| `loans` (V2) | `svc-ln-loan-lifecycle` | loans keep `customer_id`; no foreign key across services |
| Credit reservations | this service, `credit_movement` (journal per idempotency key) and `credit_reservation` (V10, open amount per reference) | new: the monolith reserved credit in-process, so it had neither; migrated `used_credit` has no reservation and counts as untracked credit |

Flyway migrations for the owned tables: `customer-infrastructure/src/main/resources/db/migration/V1__create_customer_tables.sql`, `V2__create_outbox.sql`. The service never reads monolith tables and the monolith must not read `sc_cus_profile_kyc`.

**Identity of migrated customers.** A migrated customer keeps the monolith id as text as its customer id (monolith `customers.id = 1` becomes `customer_id = '1'`), the id the loans and payments migrated by their own backfills already carry. The identity-link step (`PUT /api/v1/customers/{id}/identity-link`) sets the end user's Keycloak attribute `customer_id` to exactly that value, so the `customer_id` claim in the user's tokens equals the customer id, and the ownership checks here, in the loan service and in the payment service match the same id. `Customer.linkIdentity` accepts any id of 1 to 64 letters, digits or hyphens, so plain numeric ids such as `1` link (`CustomerIdentityLinkTest.aMigratedCustomerWithAPlainNumericIdCanBeLinked`). The parity seed customers (`CUST-12345678`, `CUST-87654321`, `CUST-11111111`, `db/fixtures/parity_seed_customers.sql`) are not monolith rows; the parity realm users' `customer_id` attributes must be exactly those ids.

**Release by reference (V10).** A release whose `reference` names one of the customer's reservations releases at most what that reservation still holds (`reserved_amount - released_amount`); partial releases are allowed, more is 422 `RELEASE_EXCEEDS_RESERVATION`. A release without a reference, or whose reference matches no reservation, releases at most the untracked used credit: `used_credit` minus the sum of open reservations. That covers balances migrated by the backfill (they have no reservation) without letting a release take another loan's reservation; more is 422 `RESERVATION_NOT_FOUND`. The monolith floored used credit at zero instead (parity CU-09, an intended change). Reserves the loan service makes before step 5 go to the monolith, so after the final backfill they are untracked here: the loan service's release for such a loan names a reference this service has no reservation for and is served from untracked credit, which is correct as long as the backfilled `used_credit` includes it (step 4 checks the totals). Races are decided by the customer row's optimistic version, as for reserve. To inspect a customer: `SELECT reference, reserved_amount, released_amount FROM sc_cus_profile_kyc.credit_reservation WHERE customer_id = '<id>'`; untracked credit is `used_credit` minus the sum of `reserved_amount - released_amount`.

The in-process `CustomerCreditSaga` (Spring `@EventListener` on loan and payment events) is removed. The loan service now reserves and releases credit synchronously over HTTP, idempotently, so a second asynchronous reservation path would double-count.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<customer service conninfo>" USD`

The currency is a required argument with no default (the script prints its usage and exits 2 without it). Decision in force: the ledger currency is USD, which the monolith evidence points to. `svc-ln-loan-lifecycle`'s `loan.customer-credit.ledger-currency` must be the same value before step 5.

1. Exports `customers` in one read-only snapshot.
2. Stages them in `backfill_stage` in the service database and transforms them (`02_transform_into_customer_service.sql`; the mapping is listed at the top of that file). The monolith stored no currency, e-mail, phone, score or income: currency comes from the run parameter and the rest stay empty.
3. Reconciles (`03_reconcile.sql`): the staged totals must equal the monolith totals taken in the same snapshot as the export, every staged customer must exist in the service, every customer the service has not changed must equal its monolith row, and no customer may have been changed by both the service and the monolith. Any problem fails the run and names the customer.

`used_credit` is copied as is. It already includes the loans that `svc-ln-loan-lifecycle` migrates, so neither backfill reserves credit again. The customer backfill does not depend on the loan or payment backfills and can run first.

Re-runs are expected until cut-over. The monolith keeps reserving and releasing credit until the write freeze (step 2), so each re-run refreshes every migrated customer the service has not changed yet (`version = legacy_synced_version`, the service's own JPA version) and never overwrites one it has. Monolith changes are detected from the data, not from `customers.version`, which the monolith never bumps (its `CustomerEntity` has no `@Version`): each copy stores `legacy_synced_hash`, the md5 of the monolith's `name|surname|credit_limit|used_credit_limit` (V6). A customer the service has changed whose monolith row no longer matches that fingerprint was changed on both sides, which means two credit ledgers; reconciliation reports it as `monolith changed it after the service did`, and it must be resolved by hand before the cut-over goes on. `scripts/migration/verify-backfill.sh` rehearses all of this on a scratch PostgreSQL (first load, idempotent re-run, a monolith change without a version bump picked up, a service change kept, a two-sided change refused both without and with a monolith version bump) and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

Customer data must have one writer at any time: the monolith's `public.customers` until step 2, nobody during the freeze (steps 2 to 4), this service from step 5 on. The order is freeze, final backfill, reconcile, then route writes; the service never takes a write on a row the final backfill has not refreshed.

Owners: **monolith squad** (enterprise-loan-management-system flags and anti-corruption client), **customer squad** (this service), **lending squad** (`svc-ln-loan-lifecycle`), **migration lead** (runs the backfill and signs off reconciliation), **change manager** (go/no-go and the freeze window). Every step needs the migration lead's sign-off in the change record before the next one starts.

| Step | Action | Owner | Validation (all must hold) | Rollback trigger (any one) | Rollback |
|---|---|---|---|---|---|
| 1 | Deploy the service with the outbox relay off (the chart default, `config.OUTBOX_RELAY_ENABLED: "false"`); run the backfill and re-run it until it reconciles. Loan keeps using the monolith for credit; nothing routes to this service yet | customer squad | `run-backfill.sh ... USD` exits 0; staged totals equal monolith totals; readiness `UP` on every replica | two consecutive backfill runs fail reconciliation | drop `sc_cus_profile_kyc`; nothing else changed |
| 2 | **Full monolith write freeze on customers**: the monolith flag refuses credit writes (reserve, release, limit changes), customer creation and profile edits with a retryable error; reads continue from the monolith. Loan is told to stop originating for the window | monolith squad, change manager | no write reaches `public.customers`: `SELECT count(*), max(updated_at) FROM customers` is unchanged across two reads 5 minutes apart; the flag's refused-write counter is the only write path that moves | the freeze has lasted 60 minutes without reaching step 5, or refused writes exceed the window agreed with the business | lift the freeze; the monolith stays the writer and nothing moved here |
| 3 | Final backfill under the freeze: `run-backfill.sh ... USD` | migration lead | exits 0; staged totals equal the monolith totals of the same snapshot; zero `monolith changed it after the service did`; zero `missing in the service` | any non-zero exit or any reported customer | lift the freeze (as step 2); fix the cause, start again from step 2 |
| 4 | Reconcile and verify: re-run `03_reconcile.sql` alone and the parity checks against the frozen monolith | migration lead, customer squad | zero problem rows; `count(*)`, `sum(credit_limit)` and `sum(used_credit)` of migrated rows equal the monolith's; zero rows with `used_credit > credit_limit` on either side; 20 random customers read through `GET /api/v1/customers/{id}/credit` equal their monolith rows | any discrepancy | lift the freeze (as step 2) |
| 5 | Route writes to the service and lift the freeze: the monolith flag sends credit writes, customer creation and profile edits through the anti-corruption client to this API, and reads too; switch `svc-ln-loan-lifecycle` to this service (`CUSTOMER_CREDIT_ADAPTER=http`, client-credentials token). This step is the precondition for loan runbook step 2 | monolith squad, lending squad | for 30 minutes: 5xx below 0.5 % of `http_server_requests_seconds_count{uri=~"/api/v1/customers.*"}`; p99 below 800 ms; `max(updated_at)` of `public.customers` unchanged (no write bypasses the flag); zero customers with `used_credit > credit_limit` | 5xx at or above 0.5 % for 5 minutes, any write landing in `public.customers`, any customer over their limit, or any 5xx on credit reserve/release that the loan service cannot replay | freeze again; copy every row changed here since step 5 (`version <> legacy_synced_version`, plus customers created here) back to `public.customers` (reverse sync, to be written and rehearsed before step 5 is scheduled); then route writes back to the monolith. Flipping the flag back without the reverse sync loses every write made here |
| 6 | Merge asyncapi-catalog PR #9 and create the topics (fintechbankx-platform-event-streaming-kafka); once mesh #11's MSK egress is applied, enable the outbox relay with `helm upgrade ... --reuse-values --set config.OUTBOX_RELAY_ENABLED=true`; consumers move to `evt.cus.customer.*.v1` | customer squad, event-streaming squad | `outbox_pending_events` drains to below 100 and `outbox_oldest_pending_age_seconds` to below 60 within 10 minutes; `outbox_parked_rows` is 0 | `outbox_parked_rows` above 0, or pending still growing after 10 minutes | relay off; events stay in the outbox (nothing lost) |
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
- [x] Events written through a transactional outbox, relayed in order with one active relay; no personal data in event payloads
- [x] Credit reserve and release are idempotent on this side: `x-idempotency-key` is required and unique per customer in the database
- [ ] Idempotent end to end: `svc-ln-loan-lifecycle` derives the key from the loan id so its retries resend it (loan PR)
- [x] Optimistic locking on the customer, so concurrent reservations cannot overdraw credit
- [x] A release takes at most its reservation (by `reference`) or, naming none, only untracked used credit; concurrent releases cannot exceed either (`CreditConcurrencyIT`)
- [x] Backfill rehearsed with reconciliation in CI, including re-runs before cut-over
- [x] Service callers get the credit position only; the full record (name, e-mail, phone, income, score) is for staff and the customer
- [x] Tokens must name this service in `aud`; service calls must come from a client on `SERVICE_CALLERS` (credit) or `SERVICE_CALLERS_KYC` (KYC status read)
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] `svc-ln-loan-lifecycle` calls with a client-credentials token (`SERVICE` role) instead of forwarding the end user's token
- [ ] Keycloak: audience mapper for `svc-cus-profile-kyc` on each calling client (fintechbankx-platform-identity-iam-keycloak-ldap)
- [ ] Keycloak: the service client `svc-cus-profile-kyc` holds the scoped FGAP v2 permission (view and manage users in group `/customers` only, identity repo 8f9024b), not realm-management `manage-users`; a user outside `/customers` answers 403, which the identity link reports as 422 `IDENTITY_USER_NOT_FOUND`
- [ ] After enabling the identity link (`IDENTITY_ADMIN_ENABLED=true`): if every link returns 422, check the FGAP permission first; `identity_directory_responses_total{status="403"}` rising with every attempt and the warning "Keycloak answered 403 reading an identity user" in the log mean the client is not scoped to `/customers`
- [ ] Namespace `customer` onboarded to the mesh (istio-injection) by the mesh-security squad
- [ ] Monolith anti-corruption client behind a flag, with a freeze mode that refuses customer creation, profile edits and credit writes (enterprise-loan-management-system)
- [ ] Reverse sync (service rows changed since step 5 back to `public.customers`) written and rehearsed, so step 5 can be rolled back without losing writes
- [ ] Topics `evt.cus.customer.*.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka)
- [ ] KYC verification (documents, screening) is not in the monolith slice and is not built here yet
- [ ] Production backfill and reconciliation report attached here
