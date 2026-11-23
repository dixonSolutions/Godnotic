#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Server control's bots: the dedicated-server commands (slots, sv_cmd touchbots, teams) and the
# real menu QuakeC on a local game (open Server control, set 6 bots, Apply, count them).
#
#   UIB_BASEDIR=<basedir with data/*.pk3 and this clone's Touch pk3dir, QuakeC compiled> \
#   [UIB_ENGINE=engine/darkplaces/darkplaces-sdl] [UIB_LIBDIR=...] scripts/test-botctl.sh
# Needs engine/darkplaces/darkplaces-dedicated (make sv-release) and darkplaces-sdl.
set -uo pipefail
cd "$(dirname "$0")/.."
: "${UIB_BASEDIR:?set UIB_BASEDIR to a basedir with game data}"
export UIB_ENGINE="${UIB_ENGINE:-$PWD/engine/darkplaces/darkplaces-sdl}"
rc=0
echo "== dedicated server: slots, bot_number, sv_cmd touchbots, teams"
python3 -u tests/botctl/test_ctl_server.py || rc=1
echo "== local game: Server control, Apply bots"
python3 -u tests/botctl/test_ctl_ui.py | grep -v '^FAIL schema' || rc=1
[ "${PIPESTATUS[0]}" = 0 ] || rc=1
echo "test-botctl: $([ $rc = 0 ] && echo all passed || echo FAILED)"
exit $rc
