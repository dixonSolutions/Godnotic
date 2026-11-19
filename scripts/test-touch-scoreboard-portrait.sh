#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The scoreboard at a portrait size (1200x1920): pl/fps/skill/suicides/damage columns dropped so
# the name fits, "Map: ... players" on its own line under the limits. Static, no engine.
# To see it: run the engine offscreen at +vid_width 1200 +vid_height 1920, +showscores, screenshot x.png.
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
python3 "$ROOT/tests/touch-scoreboard-portrait/check.py" "$ROOT"
