#!/usr/bin/env bash
set -euo pipefail

prefix="${1:?A version prefix is required}"
latest="${2:-v0.0.0}"
if [[ ! "$latest" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Invalid version tag: $latest" >&2
  exit 1
fi
major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}
case "$prefix" in
  major) major=$((10#$major + 1)); minor=0; patch=0 ;;
  minor) major=$((10#$major)); minor=$((10#$minor + 1)); patch=0 ;;
  patch) major=$((10#$major)); minor=$((10#$minor)); patch=$((10#$patch + 1)) ;;
  *) echo "Invalid version prefix: $prefix" >&2; exit 1 ;;
esac
printf 'v%s.%s.%s\n' "$major" "$minor" "$patch"
