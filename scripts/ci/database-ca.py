#!/usr/bin/env python3
"""Check the rendered Deployment trusts the Aurora CA bundle (cicd-templates 4f0f266).

The platform publishes ConfigMap rds-ca-bundle (key global-bundle.pem) in every
service namespace. The pod must mount it read-only at /etc/fintechbankx/rds-ca,
not optional (a missing bundle stops the pod instead of connecting unverified),
and export the file as DB_SSL_ROOT_CERT. Reads `helm template` output on stdin.
Usage: helm template ... | scripts/ci/database-ca.py
"""
import sys

import yaml

CONFIGMAP = "rds-ca-bundle"
KEY = "global-bundle.pem"
MOUNT_PATH = "/etc/fintechbankx/rds-ca"
ROOT_CERT = f"{MOUNT_PATH}/{KEY}"


def check(deployment):
    errors = []
    name = deployment["metadata"]["name"]
    spec = deployment["spec"]["template"]["spec"]
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
    deployments = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict) and d.get("kind") == "Deployment"]
    if not deployments:
        print("database-ca: no Deployment rendered", file=sys.stderr)
        return 1
    errors = [e for d in deployments for e in check(d)]
    for error in errors:
        print(f"database-ca: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"database-ca: {CONFIGMAP}/{KEY} mounted read-only at {MOUNT_PATH}, DB_SSL_ROOT_CERT={ROOT_CERT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
