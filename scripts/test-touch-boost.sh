#!/bin/bash
# Checks the touch auto boost decisions (engine/darkplaces/touch_boost.h):
# the gates, the weapon chosen for the launch, and the pitch it fires along.
# No engine binary needed - the header is plain C.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="$ROOT/tests/touch-boost"
CC="${CC:-cc}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$CC" -std=c99 -Wall -Wextra -Werror -Wno-unused-function \
    -I"$ROOT/engine/darkplaces" -o "$out/boost_test" "$dir/boost_test.c" -lm
"$out/boost_test"
