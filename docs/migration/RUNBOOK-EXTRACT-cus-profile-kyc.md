# RUNBOOK-EXTRACT-cus-profile-kyc

Extraction of the Customer aggregate from `enterprise-loan-management-system`
into `svc-cus-profile-kyc` (this repository), following the strangler-fig
steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cus` / `svc-cus-profile-kyc` |
| Slice | Customer aggregate: profile, contact details, credit limit and credit reservations |
| Owned data | `db_cus_profile_kyc_<env>`, schema `sc_cus_profile_kyc`: `customer`, `credit_movement`, `outbox_event` |
| Events | `evt.cus.customer.{created,contact-updated,credit-limit-updated,credit-reserved,credit-released,credit-score-updated}.v1` (contract in this repo, `api/asyncapi/svc-cus-profile-kyc.yaml`; proposed to the catalog in fintechbankx-governance-api-contracts-asyncapi-catalog PR #9, which must merge before the relay is switched on) |
| Called by | `svc-ln-loan-lifecycle`: `GET /api/v1/customers/{id}/credit` (no personal data), `POST .../credit/reserve` and `.../credit/release` with a required `x-idempotency-key` and the loan id as `reference` |
| Caller identity | Client-credentials token with the `SERVICE` realm role, `aud` containing `svc-cus-profile-kyc` (Keycloak audience mapper) and `azp` on `SERVICE_CALLERS` |

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
3. Reconciles (`03_reconcile.sql`): the staged totals must equal the monolith totals taken in the same snapshot as the export, every staged customer must exist in the service, every customer the service has not changed must equal its monolith row, and no customer may have been changed by both the service and the monolith. Any problem fails the run and names the customer.

`used_credit` is copied as is. It already includes the loans that `svc-ln-loan-lifecycle` migrates, so neither backfill reserves credit again. The customer backfill does not depend on the loan or payment backfills and can run first.

Re-runs are expected until cut-over. The monolith keeps reserving and releasing credit until step 2, so each re-run refreshes every migrated customer the service has not changed yet (`version = legacy_synced_version`) and never overwrites one it has. A customer changed on both sides means two credit ledgers; reconciliation reports it as `monolith changed it after the service did`, and it must be resolved by hand before the cut-over goes on. `scripts/migration/verify-backfill.sh` rehearses all of this on a scratch PostgreSQL (first load, idempotent re-run, a monolith change picked up, a service change kept, a two-sided change refused) and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

Credit must have one ledger at any time: the monolith's `public.customers` until step 2, this service from step 2 on.

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with `OUTBOX_RELAY_ENABLED=false`; run the backfill; reconcile. `svc-ln-loan-lifecycle` keeps its monolith credit adapter | drop `sc_cus_profile_kyc`, nothing else changed |
| 2 | Monolith: route customer reads and credit changes through an anti-corruption client to this API behind a flag (follow-up PR in the monolith). This is the credit write freeze on `public.customers` | flag off, before any credit moves here |
| 3 | Straight after the flip, re-run the backfill to pick up the last monolith changes; reconciliation must report no customer changed on both sides | flag off; re-run the backfill |
| 4 | Precondition for loan runbook step 2: switch `svc-ln-loan-lifecycle` to this service (`CUSTOMER_CREDIT_ADAPTER=http`) with its client-credentials token | loan back to the monolith adapter, which by now also calls this service through the flag, so the ledger stays here |
| 5 | Merge asyncapi-catalog PR #9 and create the topics (fintechbankx-platform-event-streaming-kafka); enable the outbox relay; consumers move to `evt.cus.customer.*.v1` | relay off; events stay in the outbox |
| 6 | Monolith stops writing `customers` altogether | flag off, monolith table is still intact |
| 7 | After the loan and payment cut-overs are complete and one full month-end cycle has passed: drop the monolith foreign keys that reference `customers` (`loans.customer_id` and any other), then drop `customers` | restore from snapshot |

## 4. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order with one active relay; no personal data in event payloads
- [x] Credit reserve and release are idempotent on this side: `x-idempotency-key` is required and unique per customer in the database
- [ ] Idempotent end to end: `svc-ln-loan-lifecycle` derives the key from the loan id so its retries resend it (loan PR)
- [x] Optimistic locking on the customer, so concurrent reservations cannot overdraw credit
- [x] Backfill rehearsed with reconciliation in CI, including re-runs before cut-over
- [x] Service callers get the credit position only; the full record (name, e-mail, phone, income, score) is for staff and the customer
- [x] Tokens must name this service in `aud`; service calls must come from a client on `SERVICE_CALLERS`
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] `svc-ln-loan-lifecycle` calls with a client-credentials token (`SERVICE` role) instead of forwarding the end user's token
- [ ] Keycloak: audience mapper for `svc-cus-profile-kyc` on each calling client (fintechbankx-platform-identity-iam-keycloak-ldap)
- [ ] Namespace `customer` onboarded to the mesh (istio-injection) by the mesh-security squad
- [ ] Monolith anti-corruption client behind a flag (enterprise-loan-management-system)
- [ ] Topics `evt.cus.customer.*.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka)
- [ ] KYC verification (documents, screening) is not in the monolith slice and is not built here yet
- [ ] Production backfill and reconciliation report attached here
