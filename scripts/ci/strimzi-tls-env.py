#!/usr/bin/env python3
"""Check the Kafka TLS env of the rendered Deployment.

Reads `helm template` output on stdin.
  --expect-secret NAME: KAFKA_TLS_CERT, KAFKA_TLS_KEY and KAFKA_TLS_CA must each come from
                        secretKeyRef NAME (the kafka-strimzi profile);
  --expect-none:        no KAFKA_TLS_* variable may be rendered (any other profile).
Exit 0 when the render matches, 1 otherwise.
"""
import argparse
import sys

import yaml

NAMES = ("KAFKA_TLS_CERT", "KAFKA_TLS_KEY", "KAFKA_TLS_CA")


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--expect-secret")
    group.add_argument("--expect-none", action="store_true")
    args = parser.parse_args()

    docs = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict)]
    env = {}
    for doc in docs:
        if doc.get("kind") != "Deployment":
            continue
        for container in doc["spec"]["template"]["spec"].get("containers", []):
            for item in container.get("env") or []:
                env[item["name"]] = item
    tls = {name: env[name] for name in env if name.startswith("KAFKA_TLS_")}
    errors = []
    if args.expect_none:
        if tls:
            errors.append(f"KAFKA_TLS_* rendered without the kafka-strimzi profile: {sorted(tls)}")
    else:
        for name in NAMES:
            ref = ((tls.get(name) or {}).get("valueFrom") or {}).get("secretKeyRef") or {}
            if ref.get("name") != args.expect_secret or not ref.get("key"):
                errors.append(f"{name} must come from secretKeyRef {args.expect_secret!r}, rendered {tls.get(name)!r}")
    for error in errors:
        print(f"strimzi-tls-env: {error}", file=sys.stderr)
    if errors:
        return 1
    print("strimzi-tls-env: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
