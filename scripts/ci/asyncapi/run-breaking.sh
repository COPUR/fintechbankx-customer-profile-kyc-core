#!/usr/bin/env bash
# Runs the catalog's AsyncAPI breaking-change gate (asyncapi-breaking.mjs, copied unchanged from
# fintechbankx-governance-api-contracts-asyncapi-catalog a7b9b9d) on this repo's api/asyncapi specs.
# The catalog script expects its specs under asyncapi/ at the repository root, so this wrapper stages
# the merge base and the working tree of api/asyncapi (with common/) as two commits of a scratch repository with that
# layout and runs the script there. BASE_REF defaults to origin/main.
set -euo pipefail

repo="$(git rev-parse --show-toplevel)"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
base_ref="${BASE_REF:-origin/main}"
if ! git rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null; then
  echo "BASE_REF ${base_ref} is not available. Fetch full history (actions/checkout fetch-depth: 0) or set BASE_REF." >&2
  exit 2
fi
merge_base="$(git merge-base "$base_ref" HEAD 2>/dev/null || git rev-parse "$base_ref")"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
git -C "$scratch" init -q
git -C "$scratch" config user.email ci@localhost
git -C "$scratch" config user.name ci
# Base: api/asyncapi at the merge base, shared schemas (common/) included; empty when the spec is new.
mkdir -p "$scratch/asyncapi"
if git cat-file -e "$merge_base:api/asyncapi" 2>/dev/null; then
  git archive "$merge_base" api/asyncapi | tar -x -C "$scratch"
  cp -R "$scratch/api/asyncapi/." "$scratch/asyncapi/"
  rm -rf "$scratch/api"
fi
git -C "$scratch" add -A
git -C "$scratch" commit -q --allow-empty -m base
git -C "$scratch" tag base
# Head: the working tree of api/asyncapi (specs, accepted-breaking files, common/).
rm -rf "$scratch/asyncapi"
cp -R "$repo/api/asyncapi" "$scratch/asyncapi"
git -C "$scratch" add -A
git -C "$scratch" commit -q --allow-empty -m head

echo "asyncapi breaking check for api/asyncapi against ${base_ref} (merge base ${merge_base:0:12})"
cd "$scratch"
BASE_REF=base node "$here/asyncapi-breaking.mjs"
