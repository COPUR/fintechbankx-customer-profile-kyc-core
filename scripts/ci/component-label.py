#!/usr/bin/env python3
"""Check the platform component label (cicd-templates 335a345) on the rendered chart.

Reads `helm template` output on stdin. App pods carry app.kubernetes.io/component=service, and the
Deployment selector, its topology-spread selectors, the Service selector and the PDB selector all
include it. The HPA targets the Deployment by name and is not checked.
"""
import sys

import yaml

KEY, VALUE = "app.kubernetes.io/component", "service"


def main():
    docs = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict)]
    errors = []

    def need(where, labels):
        if (labels or {}).get(KEY) != VALUE:
            errors.append(f"{where} lacks {KEY}={VALUE}: {labels!r}")

    kinds = {d.get("kind") for d in docs}
    for kind in ("Deployment", "Service", "PodDisruptionBudget"):
        if kind not in kinds:
            errors.append(f"no {kind} rendered")
    for doc in docs:
        kind, name = doc.get("kind"), doc.get("metadata", {}).get("name")
        if kind == "Deployment":
            spec = doc["spec"]
            need(f"Deployment {name} pod labels", spec["template"]["metadata"].get("labels"))
            need(f"Deployment {name} selector", spec["selector"].get("matchLabels"))
            for i, constraint in enumerate(spec["template"]["spec"].get("topologySpreadConstraints") or []):
                need(f"Deployment {name} topologySpreadConstraints[{i}]", constraint["labelSelector"].get("matchLabels"))
        elif kind == "Service":
            need(f"Service {name} selector", doc["spec"].get("selector"))
        elif kind == "PodDisruptionBudget":
            need(f"PodDisruptionBudget {name} selector", doc["spec"]["selector"].get("matchLabels"))
    for error in errors:
        print(f"component-label: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"component-label: {KEY}={VALUE} on pods and selectors")
    return 0


if __name__ == "__main__":
    sys.exit(main())
