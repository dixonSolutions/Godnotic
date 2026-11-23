#!/bin/bash
# Checks the smart DASH decisions (engine/darkplaces/touch_dashplan.h):
# dodge or weapon boost, up or forward, the weapon, the aim, and the refusals.
# No engine binary needed - the header is plain C.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="$ROOT/tests/touch-dash"
CC="${CC:-cc}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function \
    -I"$ROOT/engine/darkplaces" -o "$out/dash_test" "$dir/dash_test.c" -lm
"$out/dash_test"
