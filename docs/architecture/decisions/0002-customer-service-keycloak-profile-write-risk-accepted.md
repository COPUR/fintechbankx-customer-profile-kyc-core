# 0002. The customer service's power to change customer logins in Keycloak: risk accepted for now

- Status: Accepted
- Date: 2026-10-10
- Owner: Customer and KYC Squad (risk owner); platform identity squad (safeguards)
- Scope: `svc-cus-profile-kyc` identity link (`PUT /api/v1/customers/{id}/identity-link`), its Keycloak service account `svc-cus-profile-kyc`, realm `fintechbankx`

## Decision

**Accept for now.** Chosen by the user on 2026-10-10 at 12:00Z on the Platform
thread's card "Decide how to handle the customer service's power to change
customer e-mails in Keycloak". Platform keeps the change as built, with its
alerts and runbook as the safeguard.

## Facts

- The customer service's Keycloak account has `manage-members` on the
  `/customers` group (FGAP v2, identity repo 8f9024b). Keycloak has no narrower
  grant, so it can reset, disable, delete and edit any customer login.
- Disables, credential changes and deletes alert
  (`KeycloakServiceAccountAdminEventOutOfScope`, observability #11 e06915f).
- E-mail changes cannot be told apart from normal profile updates, so they do
  **not** alert.
- A stolen customer-service credential could redirect a customer's e-mail and
  then use forgot-password to take over the account.
- `linkCustomer` GETs the user and PUTs exactly `username`, `email`,
  `firstName`, `lastName`, `emailVerified` and `attributes`, because Keycloak 26
  wipes profile fields on an attributes-only PUT. There is a lost-update window
  between the GET and the PUT: a profile change made in between is overwritten
  with the values the GET returned.

## Follow-up fix

An identity-owned link service that writes only the link attributes, after
which the customer service loses `manage-members` (runbook decision 17; this
record is superseded when that service exists and the grant is removed).

## Alternative not chosen

No self-service reset (forgot-password off for customer logins). It would close
the take-over path but take a working recovery path from every customer; not
chosen.

## Safeguards

- The pinned six-key PUT (`username`, `email`, `firstName`, `lastName`,
  `emailVerified`, `attributes`; never `enabled`, credentials,
  `requiredActions`, federated identities, roles, groups or `access`) and its
  test (`KeycloakIdentityDirectoryAdapterTest`), drilled on Keycloak 26.7.5.
- Keycloak admin events for the service account, with platform's
  `KeycloakServiceAccountAdminEventOutOfScope` alert on `enabled=false`,
  credentials, reset-password, execute-actions-email, `requiredActions`,
  federated identities, role or group mappings, a `DELETE`, or anything outside
  `/customers`.
- Platform's runbook for that alert (fintechbankx-platform-observability-sre-operations).
- `IDENTITY_ADMIN_ENABLED` stays `false` until the scoped permission is in place
  (chart default); while off, linking fails closed (503).

## Reversibility

Reversible: removing `manage-members` from the service account closes the risk
at once (the identity link then fails closed), at the cost of the link feature
until the identity-owned service exists.
