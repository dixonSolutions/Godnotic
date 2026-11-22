#!/usr/bin/env bash
# A map pack is still downloading when its exact deflated levelshot is shown.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR}"
engine=${ENGINE:-$root/build/bin/xonotic}
work=$(mktemp -d); pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; if [ "${KEEP_JOINSHOT_TEST:-0}" = 1 ]; then echo "logs: $work"; else rm -rf "$work"; fi; }
trap cleanup EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
cc -std=c99 -Wall -Wextra -Werror -I"$root/engine/darkplaces" "$root/tests/touch-zipshot/zipshot_test.c" -o "$work/unit"
"$work/unit"
mkdir -p "$work/sv/data" "$work/client/data" "$work/www"
python3 - "$BASEDIR/data/xonotic-20261107-maps.pk3" "$work/www/zzz-joinshot.pk3" <<'PY'
import os,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], 'w') as out:
    out.writestr('maps/joinshotfixture.bsp',src.read('maps/stormkeep.bsp'),compress_type=zipfile.ZIP_DEFLATED)
    out.writestr('maps/joinshotfixture.jpg',src.read('maps/stormkeep.jpg'),compress_type=zipfile.ZIP_DEFLATED)
    out.writestr('padding.bin',os.urandom(8*1024*1024),compress_type=zipfile.ZIP_STORED)
PY
cp "$work/www/zzz-joinshot.pk3" "$work/sv/data/"
python3 "$root/tests/net/range_http.py" "$work/www" 28182 1024 >"$work/http.log" 2>&1 & pids+=("$!")
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port 28181 +set sv_public 0 +set bot_number 0 +set minplayers 0 +set sv_curl_defaulturl http://127.0.0.1:28182/ +set sv_curl_serverpackages maps/joinshotfixture.bsp +map joinshotfixture >"$work/sv.log" 2>&1 & pids+=("$!")
for _ in $(seq 1 600); do grep -q 'Server spawned' "$work/sv.log" && break; sleep .1; done
grep -q 'Server spawned' "$work/sv.log" || { tail -30 "$work/sv.log"; exit 1; }
cat > "$work/client/data/test.cfg" <<CFG
set touch_profile_done 1
set touch_always_autojoin 0
set fe_echo 1
fe attach 1
connect 127.0.0.1:28181
defer 35 quit
CFG
"$engine" -xonotic -basedir "$BASEDIR" -userdir "$work/client" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 +set curl_segments 1 +exec test.cfg >"$work/client.log" 2>&1 & pids+=("$!")
found=0
for _ in $(seq 1 300); do
 if find "$work/client/data/touch_joinshots" -name '*.jpg' -print -quit 2>/dev/null | grep -q .; then found=1; break; fi
 if grep -q 'Download of .*zzz-joinshot.*: OK' "$work/client.log"; then break; fi
 sleep .1
done
if [ "$found" != 1 ]; then tail -80 "$work/client.log"; cat "$work/http.log"; echo 'FAIL: no early levelshot'; exit 1; fi
shot=$(find "$work/client/data/touch_joinshots" -name '*.jpg' -print -quit)
unzip -p "$work/www/zzz-joinshot.pk3" maps/joinshotfixture.jpg > "$work/expected.jpg"
cmp "$work/expected.jpg" "$shot"
if grep -q 'Download of .*zzz-joinshot.*: OK' "$work/client.log"; then echo 'FAIL: levelshot arrived after pack'; exit 1; fi
echo 'early exact deflated levelshot before pack completion: PASS'
