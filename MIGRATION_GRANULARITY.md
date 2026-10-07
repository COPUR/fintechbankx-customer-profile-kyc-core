# Migration Granularity Notes

- Repository: `fintechbankx-customer-profile-kyc-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- dir: `customer-context` -> `.`
- file: `api/openapi/customer-context.yaml` -> `api/openapi/customer-context.yaml`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.
- 2026-10-07: seed turned into a runnable service. The service owns `sc_cus_profile_kyc` with its own Flyway migrations; the monolith's `customers` rows move with `db/backfill/run-backfill.sh` (see `docs/migration/RUNBOOK-EXTRACT-cus-profile-kyc.md`).
