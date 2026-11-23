#!/bin/bash
# Settings > Player > Local: the choices a local game makes for the player and the bots
# (touch_uib_localplayer_logic.qc compiled as C), and that nothing local reaches the
# online model and colours. No engine needed.
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp)
trap 'rm -f "$out"' EXIT
cc -Wall -Wextra -Werror -Wno-unused-function -fsanitize=address,undefined -o "$out" tests/touch-localplayer/localplayer_test.c -lm
"$out"

# Online play stays unaffected: the Local code never writes what is sent to a server.
qc=engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic
if grep -nE 'cvar_set\("(_cl_playermodel|_cl_playerskin|_cl_color|_cl_name)"|localcmd\([^)]*(playermodel|playerskin|color|name) ' $qc/touch_uib_localplayer.qc $qc/touch_uib_localplayer_logic.qc; then
	echo "FAIL: the Local settings write an online value"; exit 1
fi
echo "ok   the Local settings never write _cl_playermodel/_cl_playerskin/_cl_color"
