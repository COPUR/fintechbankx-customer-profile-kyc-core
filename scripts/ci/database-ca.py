#!/usr/bin/env python3
"""Check every rendered workload that connects to Aurora trusts the CA bundle (cicd-templates 4f0f266).

The platform publishes ConfigMap rds-ca-bundle (key global-bundle.pem) in every
service namespace. The Deployment's pods and the Flyway migration Job's pods
(decision 0001; exactly one Job is expected) must mount it read-only at
/etc/fintechbankx/rds-ca, not optional (a missing bundle stops the pod instead
of connecting unverified), and export the file as DB_SSL_ROOT_CERT, which also
turns on the startup TLS assertion. Reads `helm template` output on stdin.
Usage: helm template ... | scripts/ci/database-ca.py
"""
import sys

import yaml

CONFIGMAP = "rds-ca-bundle"
KEY = "global-bundle.pem"
MOUNT_PATH = "/etc/fintechbankx/rds-ca"
ROOT_CERT = f"{MOUNT_PATH}/{KEY}"


def check(workload):
    errors = []
    name = workload["kind"] + "/" + workload["metadata"]["name"]
    spec = workload["spec"]["template"]["spec"]
    volumes = [v for v in spec.get("volumes") or [] if (v.get("configMap") or {}).get("name") == CONFIGMAP]
    if len(volumes) != 1:
        return [f"{name}: expected one volume from ConfigMap {CONFIGMAP}, found {len(volumes)}"]
    volume = volumes[0]
    config_map = volume["configMap"]
    if config_map.get("optional") is True:
        errors.append(f"{name}: ConfigMap volume {CONFIGMAP} must not be optional")
    items = config_map.get("items") or []
    if items and not any(i.get("key") == KEY and i.get("path") == KEY for i in items):
        errors.append(f"{name}: ConfigMap volume {CONFIGMAP} does not project key {KEY} as {KEY}")
    for container in spec.get("containers") or []:
        mounts = [m for m in container.get("volumeMounts") or [] if m.get("name") == volume["name"]]
        if len(mounts) != 1 or mounts[0].get("mountPath") != MOUNT_PATH or mounts[0].get("readOnly") is not True:
            errors.append(f"{name}/{container['name']}: {CONFIGMAP} must be mounted once, readOnly, at {MOUNT_PATH}")
        env = {e.get("name"): e.get("value") for e in container.get("env") or []}
        if env.get("DB_SSL_ROOT_CERT") != ROOT_CERT:
            errors.append(f"{name}/{container['name']}: DB_SSL_ROOT_CERT is {env.get('DB_SSL_ROOT_CERT')!r}, expected {ROOT_CERT!r}")
    return errors


def main():
    docs = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict)]
    workloads = [d for d in docs if d.get("kind") in ("Deployment", "Job")]
    errors = []
    if not any(d["kind"] == "Deployment" for d in workloads):
        errors.append("no Deployment rendered")
    jobs = [d["kind"] for d in workloads].count("Job")
    if jobs != 1:
        errors.append(f"expected one migration Job, found {jobs}")
    errors += [e for d in workloads for e in check(d)]
    for error in errors:
        print(f"database-ca: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"database-ca: {CONFIGMAP}/{KEY} mounted read-only at {MOUNT_PATH}, DB_SSL_ROOT_CERT={ROOT_CERT}"
          f" in {len(workloads)} workloads")
    return 0


if __name__ == "__main__":
    sys.exit(main())
