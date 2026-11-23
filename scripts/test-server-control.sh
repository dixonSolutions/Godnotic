#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Server control (the in-game menu's rcon dialog), end to end against real engines:
#   1. the reply classification (tests/joinfail/joinfail_test.c)
#   2. tests/rcon/server_control_test.py: a dedicated server with a known rcon_password, a headless client
#      on the UI bridge. Right password unlocks (rcon_secure 0, 1 and 2), a wrong one keeps the dialog open
#      with "Wrong password", a server that does not answer gives "Server did not answer", and a local
#      game opens the controls with no password at all.
#
#   BASEDIR=<basedir with game data>  [CLIENT=engine/darkplaces/darkplaces-sdl]  [SERVER=engine/darkplaces/darkplaces-dedicated]
#   scripts/test-server-control.sh
#   SHOTS=<dir> also writes 1920x1200 screenshots of the dialog in each state (needs the client's GL-less
#   offscreen renderer; see tests/rcon/server_control_test.py)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
: "${BASEDIR:?set BASEDIR to a basedir with game data}"
export UIB_BASEDIR=$BASEDIR UIB_ENGINE=${CLIENT:-$ROOT/engine/darkplaces/darkplaces-sdl} SV_ENGINE=${SERVER:-$ROOT/engine/darkplaces/darkplaces-dedicated}
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy

echo "== reply classification"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/srvctl.XXXXXX"); trap 'rm -rf "$tmp"' EXIT
cc -std=c99 -Wall -Wextra -Werror -Iengine/darkplaces tests/joinfail/joinfail_test.c -o "$tmp/jf" && "$tmp/jf"

echo "== server control against a dedicated server"
python3 tests/rcon/server_control_test.py
