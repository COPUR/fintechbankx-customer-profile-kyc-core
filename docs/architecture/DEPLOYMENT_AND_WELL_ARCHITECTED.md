# Deployment and AWS Well-Architected mapping

How `svc-cus-profile-kyc` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
API gateway / Istio ingress ──▶ customer-profile-kyc-service pods (EKS, 3..12, HPA)
svc-ln-loan-lifecycle ─HTTP──▶    │  └─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                                  └─ outbox relay ─▶ Kafka evt.cus.customer.*.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/customer-profile-kyc-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, alarms; platform `microservice-base` module) |
| Runtime config | `customer-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics tagged `service`, `app` and `squad`; oldest-pending-age gauge `outbox_oldest_pending_age_seconds` (the platform outbox alert, over 900 s) and send-failure counter `outbox_send_failures_total{exception}`; outbox backlog gauge `outbox_pending_events` (`outbox.pending.events`) and parked-row gauge `outbox_parked_events` (alert above zero; runbook section 4); correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server that checks issuer and audience (`OIDC_AUDIENCE`), with Keycloak realm roles and method security (customers read only their own profile; services with the `SERVICE` role read only the credit position and move credit, and only when the calling client (`azp`) is on `SERVICE_CALLERS`; the KYC status read uses its own list `SERVICE_CALLERS_KYC`); DPoP is not required for internal client-credentials calls (platform contract; it applies to open-finance TPP clients), which rely on mesh-wide STRICT mTLS; the mesh repo owns the default-deny and ALLOW `AuthorizationPolicy` rules, and the mesh repo also owns the ingress `NetworkPolicy` (mesh #11), so the chart ships none; non-root, read-only root filesystem, all capabilities dropped; DB credential `<env>/customer-profile-kyc-service/db-app` from Secrets Manager via the platform `ClusterSecretStore` `aws-secrets-manager` (KMS key tagged `fintechbankx.io/secrets=true`), never in config; the pods' IRSA role has no secret access, only MSK IAM write to `evt.cus.customer.*`; KMS-encrypted storage, snapshots, logs and secrets with rotation; TLS enforced (`rds.force_ssl`); IRSA least-privilege policy; DB reachable only from the workload security group; data tagged `restricted-pii`; events carry ids, amounts and scores only (no names, e-mail or phone); error responses never disclose credit figures | `SecurityConfiguration`, `ServiceCallerPolicy`, `CustomerController`, `KycStatusController`, `ApiExceptionHandler`, `deployment.yaml`, `externalsecret.yaml`, `main.tf`, `CustomerEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ with reader failover, 35-day PITR in prod, deletion protection and final snapshot; pods spread across zones, PDB, zero-unavailable rolling updates, graceful shutdown; transactional outbox (no lost events), ordered single-relay publishing, idempotent Kafka producer; idempotent credit reserve and release (`credit_movement`, unique key per customer); optimistic locking on the customer | `main.tf`, `deployment.yaml`, `pdb.yaml`, `OutboxRelay`, `CustomerManagementService`, `V1__create_customer_tables.sql`, `JpaCustomerRepositoryAdapter` |
| Performance efficiency | Stateless pods scaled by HPA on CPU (JVM heap does not shrink, so memory is not a scaling signal); DB connection alarm sized from `hpa_max_replicas × db_pool_max`; Aurora Serverless v2 scales ACUs; virtual threads for request handling; case-insensitive unique index for e-mail lookups; partial index for the outbox queue; JDBC batching | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_customer_tables.sql`, `V2__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); outbox rows purged after 7 days; log retention 30 days outside prod | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak (HPA scale-down policy, Serverless ACUs); layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- `svc-ln-loan-lifecycle` still forwards the end user's token. It must move to a client-credentials token with the `SERVICE` realm role and the `svc-cus-profile-kyc` audience, use `GET /credit`, and send deterministic idempotency keys before cutover (runbook step 4).
- Keycloak clients need an audience mapper for `svc-cus-profile-kyc`; tokens without it are rejected.
- Identity link (`PUT .../identity-link`): the service only links existing Keycloak users, it never creates them. Its client holds the scoped FGAP v2 permission (users in group `/customers` only, identity repo 8f9024b); a 403 on reading a user means "not a customer user" and is answered 422 `IDENTITY_USER_NOT_FOUND`.
- The `customer` namespace must be onboarded to the mesh (sidecar injection) before the STRICT policies are applied.
- Kafka topics and ACLs for `evt.cus.customer.*.v1` are not yet created on the platform cluster.
- The application DB role (`customer_profile_app`) is created by a DBA bootstrap step, not by Terraform, so Terraform never holds the password.
- KYC verification (identity documents, sanctions and PEP screening) is not part of the monolith slice and is not built yet.
- `microservice-base` is pinned to a commit; move to a release tag once the modules repo publishes one. No Terraform lock file is committed yet (providers are bounded with `~>`); commit one from a machine that can reach the registry.
- No load test yet; HPA targets are starting values.
