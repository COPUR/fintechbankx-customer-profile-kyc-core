#!/usr/bin/env python3
"""Check the rendered Deployment's pod template carries fintechbankx.io/service-id.

Platform outbox and SLO rules select on the service_id label, which comes from this
pod label. Reads `helm template` output on stdin.
Usage: helm template ... | scripts/ci/pod-service-id.py --expect svc-cus-profile-kyc
"""
import argparse
import sys

import yaml


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect", required=True)
    args = parser.parse_args()
    deployments = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict) and d.get("kind") == "Deployment"]
    if not deployments:
        print("pod-service-id: no Deployment rendered", file=sys.stderr)
        return 1
    errors = []
    for deployment in deployments:
        labels = deployment["spec"]["template"]["metadata"].get("labels") or {}
        value = labels.get("fintechbankx.io/service-id")
        if value != args.expect:
            errors.append(f"{deployment['metadata']['name']}: fintechbankx.io/service-id is {value!r}, expected {args.expect!r}")
    for error in errors:
        print(f"pod-service-id: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"pod-service-id: fintechbankx.io/service-id={args.expect}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
