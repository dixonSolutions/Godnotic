#!/bin/bash
# Uploaded player models: the compatibility check and the zip writer/reader
# (engine/darkplaces/touch_playermodel_logic.h), no engine needed.
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp)
trap 'rm -f "$out"' EXIT
cc -Wall -Wextra -Werror -fsanitize=address,undefined -I engine/darkplaces -o "$out" tests/touch-playermodel/playermodel_test.c
"$out"
