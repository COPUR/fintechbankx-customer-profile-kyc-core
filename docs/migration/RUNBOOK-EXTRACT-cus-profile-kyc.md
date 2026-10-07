# RUNBOOK-EXTRACT-cus-profile-kyc

Extraction of the Customer aggregate from `enterprise-loan-management-system`
into `svc-cus-profile-kyc` (this repository), following the strangler-fig
steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cus` / `svc-cus-profile-kyc` |
| Slice | Customer aggregate: profile, contact details, credit limit and credit reservations |
| Owned data | `db_cus_profile_kyc_<env>`, schema `sc_cus_profile_kyc`: `customer`, `credit_movement`, `outbox_event` |
| Events | `evt.cus.customer.{created,contact-updated,credit-limit-updated,credit-reserved,credit-released,credit-score-updated}.v1` (AsyncAPI `svc-cus-profile-kyc.yaml` in the asyncapi catalog) |
| Called by | `svc-ln-loan-lifecycle`: `GET /api/v1/customers/{id}`, `POST .../credit/reserve` and `.../credit/release` with `x-idempotency-key` |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `customers` (V1) | this service, `customer` | id kept as text (`'42'`), which loans and payments already reference; numeric id kept in `legacy_customer_id` |
| `update_customers_updated_at` trigger | removed | `Customer` keeps `updated_at` |
| `customer_management` schema (V1__Create_customer_management_schema) | not migrated | parallel schema never used by the running application; confirm it has no rows before the cutover |
| `loans` (V2) | `svc-ln-loan-lifecycle` | loans keep `customer_id`; no foreign key across services |
| Credit reservations | this service, `credit_movement` | new: the monolith reserved credit in-process, so it had no journal |

Flyway migrations for the owned tables: `customer-infrastructure/src/main/resources/db/migration/V1__create_customer_tables.sql`, `V2__create_outbox.sql`. The service never reads monolith tables and the monolith must not read `sc_cus_profile_kyc`.

The in-process `CustomerCreditSaga` (Spring `@EventListener` on loan and payment events) is removed. The loan service now reserves and releases credit synchronously over HTTP, idempotently, so a second asynchronous reservation path would double-count.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<customer service conninfo>" AED`

1. Exports `customers` in one read-only snapshot.
2. Stages them in `backfill_stage` in the service database and transforms them (`02_transform_into_customer_service.sql`; the mapping is listed at the top of that file). The monolith stored no currency, e-mail, phone, score or income: currency comes from the run parameter and the rest stay empty.
3. Compares row counts, credit limit and used credit totals, plus per-customer invariants (`03_reconcile.sql`). Any difference fails the run.

`used_credit` is copied as is. It already includes the loans that `svc-ln-loan-lifecycle` migrates, so neither backfill reserves credit again. The customer backfill does not depend on the loan or payment backfills and can run first. It is idempotent (`ON CONFLICT DO NOTHING` on the id), so a re-run never overwrites a change the service has made. `scripts/migration/verify-backfill.sh` rehearses it on a scratch PostgreSQL and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with `OUTBOX_RELAY_ENABLED=false`; run the backfill; reconcile | drop `sc_cus_profile_kyc`, nothing else changed |
| 2 | Monolith: route customer reads and credit changes through an anti-corruption client to this API behind a flag (follow-up PR in the monolith) | flag off |
| 3 | Re-run the backfill for customers created before the flag flipped; reconcile again | flag off |
| 4 | Point `svc-ln-loan-lifecycle` at this service (`CUSTOMER_SERVICE_BASE_URL`) with a client-credentials token holding the `SERVICE` realm role | point back at the monolith |
| 5 | Enable the outbox relay; consumers move to `evt.cus.customer.*.v1` | relay off; events stay in the outbox |
| 6 | Monolith stops writing `customers` | flag off, monolith table is still intact |
| 7 | After one full month-end cycle: drop the monolith table | restore from snapshot |

## 4. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order with one active relay; no personal data in event payloads
- [x] Idempotent credit reserve and release (`x-idempotency-key`, unique per customer in the database)
- [x] Optimistic locking on the customer, so concurrent reservations cannot overdraw credit
- [x] Backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] `svc-ln-loan-lifecycle` calls with a client-credentials token (`SERVICE` role) instead of forwarding the end user's token
- [ ] Monolith anti-corruption client behind a flag (enterprise-loan-management-system)
- [ ] Topics `evt.cus.customer.*.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka)
- [ ] KYC verification (documents, screening) is not in the monolith slice and is not built here yet
- [ ] Production backfill and reconciliation report attached here
