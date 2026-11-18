#!/usr/bin/env bash
set -euo pipefail

script="$(dirname "$0")/next-version.sh"
test "$(bash "$script" major "")" = v1.0.0
test "$(bash "$script" minor "")" = v0.1.0
test "$(bash "$script" patch "")" = v0.0.1
test "$(bash "$script" major v1.4.3)" = v2.0.0
test "$(bash "$script" minor v1.4.3)" = v1.5.0
test "$(bash "$script" patch v1.4.3)" = v1.4.4
if bash "$script" invalid v1.0.0 >/dev/null 2>&1 ||
   bash "$script" major invalid >/dev/null 2>&1; then
  echo "Invalid version input was accepted." >&2
  exit 1
fi
