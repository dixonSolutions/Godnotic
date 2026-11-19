#!/usr/bin/env bash
# A local game shows its map's picture from the moment the map is picked: the
# native card's connect snapshot names the map and the picture file at once,
# before the server exists, and says the world is not drawable yet.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/build/bin/xonotic}
: "${BASEDIR:?set BASEDIR to a game directory with data/}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || exit 77
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
mkdir -p "$work/data"
cat > "$work/data/lp.cfg" <<CFG
set touch_profile_done 1
set _cl_name LocalPreviewTest
set vid_touchscreen 1
set fe_echo 1
fe attach 1
fe play dm stormkeep 4
defer 20 quit
CFG
timeout 90 "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$work" \
    +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 \
    +exec lp.cfg > "$work/log" 2>&1 || true
# the first connect snapshot that names the map: picture file there, world not yet drawable
line=$(grep -a '^FE> connect .*"join_map":"stormkeep"' "$work/log" | head -1 || true)
if [ -z "$line" ]; then
    tail -40 "$work/log" >&2
    echo "FAIL: no connect snapshot named the local game's map" >&2
    exit 1
fi
case "$line" in
    *'"local":true'*) ;;
    *) echo "FAIL: not marked local: $line" >&2; exit 1 ;;
esac
case "$line" in
    *'"levelshot":"'*stormkeep.jpg'"'*) ;;
    *) echo "FAIL: no picture file in the first snapshot: $line" >&2; exit 1 ;;
esac
case "$line" in
    *'"world":false'*) ;;
    *) echo "FAIL: world drawable at the first snapshot: $line" >&2; exit 1 ;;
esac
echo "PASS: a local game names its map and picture in the first snapshot, before the world"
