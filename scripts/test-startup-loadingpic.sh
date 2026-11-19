#!/usr/bin/env bash
# The startup loading picture is decoded once, not again by the loading-screen
# redraw that runs inside its own load.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR to a game data directory}"
ENGINE="${ENGINE:-$root/build/bin/xonotic}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
mkdir -p "$work/u/data"
timeout 20 "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$work/u" +vid_fullscreen 0 +vid_width 1280 +vid_height 720 \
    +set _touch_assets_ready 1 +set developer 1 +set developer_loading 1 +set touch_loadtime 1 +defer 3 quit > "$work/log" 2>&1 || true
grep -aq 'LOADTIME menu_presented' "$work/log" || { echo "FAIL: no menu frame" >&2; exit 1; }
n=$(grep -ac '^loaded image gfx/loading' "$work/log" || true)
[ "$n" = 1 ] || { echo "FAIL: loading picture decoded $n times" >&2; exit 1; }
echo "PASS: loading picture decoded once at startup"
