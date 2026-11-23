#!/bin/bash
# Checks the size and placement maths of the in-match health / armour readout
# (engine/darkplaces/touch_vitals.h). No engine build, no display.
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
CC="${CC:-cc}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$CC" -std=c99 -Wall -Wextra -Werror -I"$ROOT/engine/darkplaces" -o "$out/vitals_test" "$ROOT/tests/touch-hud/vitals_test.c" -lm
"$out/vitals_test"
