#!/usr/bin/env bash
# The server's own download (csprogs.dat over the game connection, a public server whose CSQC is not
# ours) is a download on the join card: the connect topic names it, counts its bytes, gives a speed
# and a total, and the card never reads "Receiving game data" (the signon wait) while it runs.
# A local server serves a csprogs.dat that differs from the client's (the stock one plus padding), so
# the client has to download it; the test reads the front-end bridge log (`FE> connect {...}`).
# tests/touch-joinseq/csqc_check.py has the assertions.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR}"
engine=${ENGINE:-$root/build/bin/xonotic}
map=${CSQCDL_MAP:-stormkeep}
work=$(mktemp -d); pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; if [ "${KEEP_CSQCDL:-0}" = 1 ]; then echo "logs: $work"; else rm -rf "$work"; fi; }
trap cleanup EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
mkdir -p "$work/sv/data" "$work/c/data"
# the server's csprogs.dat: the one in the game data, with padding that changes its CRC and size
src=$(readlink -f "$BASEDIR"/data/zz-xonotic-data.pk3dir 2> /dev/null || true)
[ -f "$src/csprogs.dat" ] || src=$(find -L "$BASEDIR"/data -maxdepth 2 -name csprogs.dat -print -quit | xargs -r dirname)
[ -f "$src/csprogs.dat" ] || { echo "SKIP: no csprogs.dat in $BASEDIR"; exit 77; }
cp "$src/csprogs.dat" "$work/sv/data/csprogs.dat"
head -c 300000 /dev/urandom >> "$work/sv/data/csprogs.dat"
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port 26973 +set sv_public 0 +set bot_number 0 +set minplayers 0 +map $map > "$work/sv.log" 2>&1 & pids+=("$!")
for _ in $(seq 1 300); do grep -q 'Server spawned' "$work/sv.log" && break; sleep .1; done
grep -q 'Server spawned' "$work/sv.log" || { echo "FAIL: the server did not start"; tail -5 "$work/sv.log"; exit 1; }
cat > "$work/c/data/t.cfg" <<CFG
set touch_profile_done 1
set touch_always_autojoin 0
set _cl_name CsqcDl
set vid_touchscreen 1
set fe_echo 1
echo MET-CVARS \$touch_net_metered \$touch_net_metered_shot_kb
fe attach 1
connect 127.0.0.1:26973
defer 40 quit
CFG
taskset -c "${CSQCDL_CPUS:-4-7}" "$engine" -xonotic -basedir "$BASEDIR" -userdir "$work/c" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 +exec t.cfg > "$work/c.log" 2>&1 & pids+=("$!")
# done when the download has gone on for a few seconds, or the client is in the game
for _ in $(seq 1 120); do
  grep -q 'Downloaded "dlcache/csprogs.dat' "$work/c.log" && break
  grep -q '^FE> app .*"state":"ingame"' "$work/c.log" && break
  sleep .5
done
sleep 1
python3 -I "$root/tests/touch-joinseq/csqc_check.py" "$work/c.log"
python3 -I "$root/tests/touch-joinseq/preview_steady.py" "$work/c.log"
