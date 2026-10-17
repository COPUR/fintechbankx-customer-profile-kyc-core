#!/usr/bin/env bash
# Proves the listed password exemptions in publication-guardrails.yml are exact: each listed text passes only at its
# listed path, the same text in any other file is still reported, and an unlisted literal in a listed file is reported.
# Usage: bash scripts/ci/secret-scan-exemptions-test.sh (needs python3 with PyYAML, curl, tar and sha256sum).
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
python3 - .github/workflows/publication-guardrails.yml "$work/scan.sh" <<'PY'
import sys, yaml
steps = yaml.safe_load(open(sys.argv[1]))["jobs"]["secret-pattern-scan"]["steps"]
open(sys.argv[2], "w").write(next(s["run"] for s in steps if s.get("name") == "Scan secret-like patterns"))
PY
mapfile -t entries < <(grep -oE "^ *'\./[^']+'$" "$work/scan.sh" | sed -E "s/^ *'(.*)'$/\1/")
[ "${#entries[@]}" -gt 0 ] || { echo "no listed exemptions found"; exit 1; }

fail=0
# expect <want exit> <tree> <description>
expect() {
  local rc=0
  (cd "$2" && bash -eo pipefail "$work/scan.sh" >/dev/null 2>&1) || rc=$?
  if [ "$rc" != "$1" ]; then echo "FAIL exit=$rc want=$1: $3"; fail=1; fi
}
for entry in "${entries[@]}"; do
  path=${entry%%:*}; text=${entry#*:}
  tree=$(mktemp -d -p "$work")
  mkdir -p "$tree/$(dirname "$path")"; printf '%s;\n' "$text" > "$tree/$path"
  expect 0 "$tree" "listed at its path: $entry"
  other=$(mktemp -d -p "$work")
  mkdir -p "$other/src/test/java"; printf '%s;\n' "$text" > "$other/src/test/java/NotListed.java"
  expect 1 "$other" "same text, unlisted path: $text"
  extra=$(mktemp -d -p "$work")
  mkdir -p "$extra/$(dirname "$path")"; printf '%s;\nString X_%s = "hunter2";\n' "$text" PASSWORD > "$extra/$path"
  expect 1 "$extra" "unlisted literal in a listed file: $path"
done
[ "$fail" -eq 0 ] && echo "exemption fixtures passed (${#entries[@]} entries)" || { echo "exemption fixtures failed"; exit 1; }
