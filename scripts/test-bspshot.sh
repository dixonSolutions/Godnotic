#!/usr/bin/env bash
# The map picture made from a Quake 1 BSP (touch_bspshot.h) and the ZIP directory reader for it (touch_zipshot.h).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for t in zipshot bspshot; do
  "${CC:-cc}" -std=c99 -Wall -Wextra -Werror -I"$root/engine/darkplaces" "$root/tests/touch-zipshot/${t}_test.c" -o "$work/$t" -lm
  "$work/$t"
done
