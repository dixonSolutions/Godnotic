#!/usr/bin/env bash
# Offscreen listen-server regression through the tablet's native fe play path.
# BASEDIR must contain data/ with maps, game assets and this checkout's pk3dir.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/build/bin/xonotic}
: "${BASEDIR:?set BASEDIR to a game directory with data/}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || exit 77
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
for mode in ctf dm; do
    map=implosion
    [ "$mode" = dm ] && map=stormkeep
    mkdir -p "$work/$mode/data"
    cat > "$work/$mode/data/localjoin.cfg" <<CFG
set touch_profile_done 1
set _cl_name LocalJoinTest
set cl_allow_uid2name 0
set vid_touchscreen 1
set touch_join_dialog_auto 2
set sv_spectate 1
fe play $mode $map 4
alias _lj_wait0 "defer 0.1 _lj_wait"
alias _lj_wait1 "togglemenu 0; touch_hud_state; defer 1 _lj_join"
alias _lj_wait "_lj_wait\$_touch_loadtime_game"
alias _lj_join "touch_join auto; defer 2 _lj_check"
alias _lj_check "touch_hud_state; quit"
_lj_wait
CFG
    timeout 90 "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$work/$mode" \
        +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 \
        +exec localjoin.cfg > "$work/$mode.log" 2>&1
    if ! grep -aq 'TOUCHHUD mode=playing ' "$work/$mode.log"; then
        cat "$work/$mode.log" >&2
        echo "FAIL: $mode local player did not join within two seconds" >&2
        exit 1
    fi
    echo "PASS: $mode local player playing within two seconds of choice"
done
