#!/usr/bin/env bash
# Regression: the menu-idle warm-up (touch_warm) must not load a map inside the
# loading plaque frame of a real map load. SV_SpawnServer draws that frame while
# the client is still disconnected; a warm world load nested there segfaulted the
# engine (or Host_Error'd) at map load, in about every second offscreen launch
# whose `map` fired as the idle timer expired. Runs N local stormkeep matches with
# bots at portrait and landscape sizes and fails on any crash.
#   BASEDIR=<dir holding data/> [ENGINE=...] [N=8] scripts/test-touch-warm-spawn.sh
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/build/bin/xonotic}
: "${BASEDIR:?set BASEDIR to a game directory with data/}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || exit 77
N=${N:-8}
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
crashes=0
for size in 1200x1920 1920x1200; do
    for i in $(seq 1 "$N"); do
        u=$work/u-$size-$i
        mkdir -p "$u/data"
        # warm target = last played map, and `map` fires the moment idle (2 s) expires
        cat > "$u/data/spawn.cfg" <<CFG
set touch_profile_done 1
set _touch_warm_last stormkeep
set bot_number 4
set g_warmup 0
defer 2 "map stormkeep"
defer 12 quit
CFG
        timeout 90 "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$u" +vid_fullscreen 0 \
            +vid_width "${size%x*}" +vid_height "${size#*x}" +set _touch_assets_ready 1 \
            +set touch_warm 1 +set touch_warm_idle 2 +exec spawn.cfg > "$u.log" 2>&1
        rc=$?
        if grep -aq 'Engine Crash' "$u.log" || [ "$rc" -ge 124 ] || ! grep -aq 'SpawnServer: stormkeep' "$u.log"; then
            crashes=$((crashes + 1))
            tail -30 "$u.log" >&2
            echo "FAIL: $size run $i rc=$rc" >&2
        fi
    done
done
[ "$crashes" -eq 0 ] || { echo "FAIL: $crashes crashes in $((2 * N)) loads" >&2; exit 1; }
echo "PASS: $((2 * N)) stormkeep loads (portrait and landscape), no crash"
