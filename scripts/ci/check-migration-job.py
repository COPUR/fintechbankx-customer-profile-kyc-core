#!/usr/bin/env python3
"""Reads a rendered chart on stdin and checks the Flyway migration Job split
(platform convention, cicd-templates 335a345; decision 0001). argv[1] is the
chart's service account name.

- The app pods (Deployment) never reference the db-migration secret, by
  envFrom, env, volume or projected volume, and carry no DB_MIGRATION_* env.
- Exactly one Job: a Helm pre-install and pre-upgrade hook with backoffLimit,
  activeDeadlineSeconds and ttlSecondsAfterFinished; restartPolicy Never; pod
  labels app.kubernetes.io/name=<service account> (the mesh grants Aurora
  egress on it) and app.kubernetes.io/component=db-migration.
- The Job's pods carry sidecar.istio.io/inject "false": Aurora egress is a
  Kubernetes NetworkPolicy on the name label, so the Job needs no proxy, and
  without native sidecars an injected proxy keeps the Job from completing.
- The Job runs the app image with the argument "migrate", takes the
  db-migration secret, the same DB_URL as the app's ConfigMap and DB_USERNAME,
  and has the app's pod and container security contexts. The RDS CA mount and
  DB_SSL_ROOT_CERT are checked by the workflow's database CA check.
- The Job's ServiceAccount and the db-migration ExternalSecret are hooks
  created before the Job (lower hook weight), so they exist on a first
  install, and deleted once the hooks succeed; the ServiceAccount has no IAM
  role and mounts no API token.
- No Service, PodDisruptionBudget, NetworkPolicy, Deployment or topology-spread
  selector rendered here matches the Job's pod labels.
"""
import json
import sys

import yaml

COMPONENT = ("app.kubernetes.io/component", "db-migration")
NAME = "app.kubernetes.io/name"
SIDECAR = ("sidecar.istio.io/inject", "false")
HOOK, WEIGHT, DELETE_POLICY = "helm.sh/hook", "helm.sh/hook-weight", "helm.sh/hook-delete-policy"

service_account = sys.argv[1]
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
problems = []


def of(kind):
    return [d for d in docs if d.get("kind") == kind]


def annotations(doc):
    return doc["metadata"].get("annotations") or {}


def hooks(doc):
    return {h.strip() for h in (annotations(doc).get(HOOK) or "").split(",") if h.strip()}


def delete_policies(doc):
    return {h.strip() for h in (annotations(doc).get(DELETE_POLICY) or "").split(",") if h.strip()}


def weight(doc):
    return int(annotations(doc).get(WEIGHT, "0"))


def selector_matches(selector, labels):
    """Kubernetes label selector semantics; a plain map (Service) is matchLabels."""
    if selector is None:
        return False
    if "matchLabels" not in selector and "matchExpressions" not in selector:
        selector = {"matchLabels": selector}
    for key, value in (selector.get("matchLabels") or {}).items():
        if labels.get(key) != value:
            return False
    for expression in selector.get("matchExpressions") or []:
        key, operator, values = expression["key"], expression["operator"], expression.get("values") or []
        if operator == "In" and labels.get(key) not in values:
            return False
        if operator == "NotIn" and key in labels and labels[key] in values:
            return False
        if operator == "Exists" and key not in labels:
            return False
        if operator == "DoesNotExist" and key in labels:
            return False
    return True


deployments, jobs, configmaps = of("Deployment"), of("Job"), of("ConfigMap")
if len(deployments) != 1:
    problems.append(f"expected one Deployment, found {len(deployments)}")
if len(jobs) != 1:
    problems.append(f"expected one migration Job, found {len(jobs)}")

migration_secrets = [e for e in of("ExternalSecret") if any(
    d.get("secretKey", "").startswith("DB_MIGRATION_") for d in e["spec"].get("data") or [])]
if len(migration_secrets) != 1:
    problems.append(f"expected one ExternalSecret with DB_MIGRATION_* keys, found {len(migration_secrets)}")

if not problems:
    deployment, job, external_secret = deployments[0], jobs[0], migration_secrets[0]
    secret_name = external_secret["spec"]["target"]["name"]
    app_pod = deployment["spec"]["template"]
    app_container = app_pod["spec"]["containers"][0]

    # 1. The app pods never see the schema owner's credential.
    if secret_name in json.dumps(app_pod):
        problems.append(f"Deployment pod template references the migration secret {secret_name}")
    for container in app_pod["spec"].get("containers", []) + app_pod["spec"].get("initContainers", []):
        for env in container.get("env") or []:
            if env["name"].startswith("DB_MIGRATION_"):
                problems.append(f"Deployment container {container['name']} sets {env['name']}")

    # 2. The Job: hook, limits, labels, no sidecar.
    name = job["metadata"]["name"]
    if not {"pre-install", "pre-upgrade"} <= hooks(job):
        problems.append(f"Job {name}: {HOOK} must include pre-install and pre-upgrade, got {sorted(hooks(job))}")
    spec = job["spec"]
    for field in ("backoffLimit", "activeDeadlineSeconds", "ttlSecondsAfterFinished"):
        if not isinstance(spec.get(field), int) or spec[field] < (0 if field == "backoffLimit" else 1):
            problems.append(f"Job {name}: spec.{field} must be set, got {spec.get(field)!r}")
    pod = spec["template"]
    labels = pod["metadata"].get("labels") or {}
    if labels.get(NAME) != service_account:
        problems.append(f"Job {name}: pod label {NAME} must be {service_account!r}, got {labels.get(NAME)!r}")
    if labels.get(COMPONENT[0]) != COMPONENT[1]:
        problems.append(f"Job {name}: pod label {COMPONENT[0]} must be {COMPONENT[1]!r}, got {labels.get(COMPONENT[0])!r}")
    if labels.get(SIDECAR[0]) != SIDECAR[1]:
        problems.append(f"Job {name}: pod label {SIDECAR[0]} must be {SIDECAR[1]!r} (an injected proxy keeps the Job "
                        f"from completing; Aurora egress is a NetworkPolicy on {NAME}), got {labels.get(SIDECAR[0])!r}")
    if (pod["metadata"].get("annotations") or {}).get(SIDECAR[0], SIDECAR[1]) != SIDECAR[1]:
        problems.append(f"Job {name}: pod annotation {SIDECAR[0]} contradicts the label")
    if pod["spec"].get("restartPolicy") != "Never":
        problems.append(f"Job {name}: restartPolicy must be Never, got {pod['spec'].get('restartPolicy')!r}")

    # 3. Same image in migrate mode, the migration credential, the app's DB_URL, the app's security contexts.
    containers = pod["spec"].get("containers") or []
    if len(containers) != 1:
        problems.append(f"Job {name}: expected one container, found {len(containers)}")
    else:
        container = containers[0]
        if container.get("image") != app_container.get("image"):
            problems.append(f"Job {name}: image {container.get('image')!r} is not the app image {app_container.get('image')!r}")
        if container.get("args") != ["migrate"] or container.get("command"):
            problems.append(f"Job {name}: must run the image entrypoint with args [migrate], got command "
                            f"{container.get('command')!r} args {container.get('args')!r}")
        refs = [e.get("secretRef", {}).get("name") for e in container.get("envFrom") or []]
        if secret_name not in refs:
            problems.append(f"Job {name}: envFrom must include secretRef {secret_name}, got {refs!r}")
        if any(e.get("configMapRef") for e in container.get("envFrom") or []):
            problems.append(f"Job {name}: must not read the app ConfigMap (it does not exist yet on a first install)")
        env = {e["name"]: e.get("value") for e in container.get("env") or []}
        app_config = (configmaps[0].get("data") or {}) if len(configmaps) == 1 else {}
        for key in ("DB_URL", "DB_USERNAME"):
            if not app_config.get(key) or env.get(key) != app_config.get(key):
                problems.append(f"Job {name}: {key} {env.get(key)!r} differs from the app ConfigMap's {app_config.get(key)!r}")
        if container.get("securityContext") != app_container.get("securityContext"):
            problems.append(f"Job {name}: container securityContext {container.get('securityContext')!r} differs from the app's")
        context = container.get("securityContext") or {}
        if context.get("readOnlyRootFilesystem") is not True or context.get("allowPrivilegeEscalation") is not False \
                or (context.get("capabilities") or {}).get("drop") != ["ALL"]:
            problems.append(f"Job {name}: needs readOnlyRootFilesystem, no privilege escalation, all capabilities dropped")
    if pod["spec"].get("securityContext") != app_pod["spec"].get("securityContext") \
            or (pod["spec"].get("securityContext") or {}).get("runAsNonRoot") is not True:
        problems.append(f"Job {name}: pod securityContext must be the app's (runAsNonRoot)")

    # 4. What the Job needs exists before it on a first install, and goes away once the hooks succeed.
    job_sa = pod["spec"].get("serviceAccountName")
    accounts = [s for s in of("ServiceAccount") if s["metadata"]["name"] == job_sa]
    if job_sa == service_account or len(accounts) != 1:
        problems.append(f"Job {name}: needs its own ServiceAccount rendered by the chart, got {job_sa!r}")
    else:
        account = accounts[0]
        if "pre-install" not in hooks(account) or weight(account) >= weight(job):
            problems.append(f"ServiceAccount {job_sa}: must be a pre-install hook weighted before the Job")
        if "eks.amazonaws.com/role-arn" in annotations(account):
            problems.append(f"ServiceAccount {job_sa}: the migration Job needs no IAM role")
        if account.get("automountServiceAccountToken") is not False:
            problems.append(f"ServiceAccount {job_sa}: automountServiceAccountToken must be false")
    es_name = external_secret["metadata"]["name"]
    if "pre-install" not in hooks(external_secret) or weight(external_secret) >= weight(job):
        problems.append(f"ExternalSecret {es_name}: must be a pre-install hook weighted before the Job")
    if "hook-succeeded" not in delete_policies(external_secret):
        problems.append(f"ExternalSecret {es_name}: {DELETE_POLICY} must include hook-succeeded, so the owner credential "
                        f"leaves the namespace once the migration ran")

    # 5. Nothing that selects app pods selects the Job's pods.
    selectors = [(f"Service/{d['metadata']['name']}", d["spec"].get("selector")) for d in of("Service")]
    selectors += [(f"PodDisruptionBudget/{d['metadata']['name']}", d["spec"].get("selector")) for d in of("PodDisruptionBudget")]
    selectors += [(f"NetworkPolicy/{d['metadata']['name']}", d["spec"].get("podSelector")) for d in of("NetworkPolicy")]
    for d in deployments:
        selectors.append((f"Deployment/{d['metadata']['name']} selector", d["spec"].get("selector")))
        for i, c in enumerate(d["spec"]["template"]["spec"].get("topologySpreadConstraints") or []):
            selectors.append((f"Deployment/{d['metadata']['name']} topologySpreadConstraints[{i}]", c.get("labelSelector")))
    for where, selector in selectors:
        if selector_matches(selector, labels):
            problems.append(f"{where} selects the migration Job's pods ({COMPONENT[0]}={labels.get(COMPONENT[0])})")

if problems:
    sys.exit("migration Job check failed:\n  " + "\n  ".join(problems))
print(f"migration Job: hook pre-install,pre-upgrade, pods {NAME}={service_account} {COMPONENT[0]}={COMPONENT[1]} "
      f"{SIDECAR[0]}={SIDECAR[1]}, app image with args [migrate], migration secret only in the Job (deleted once the "
      "hooks succeed), no selector matches its pods")
