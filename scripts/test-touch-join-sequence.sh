#!/usr/bin/env bash
# One client joins a server, rides a map change on it, then joins a second server on another map.
# The front-end bridge log must show: the right picture for every map (never the previous
# server's), and one join card's screen sequence (tests/touch-joinseq/check.py).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR}"
engine=${ENGINE:-$root/build/bin/xonotic}
a=${JOINSEQ_A:-stormkeep}; b=${JOINSEQ_B:-boil}; c=${JOINSEQ_C:-atelier}
work=$(mktemp -d); pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; if [ "${KEEP_JOINSEQ:-0}" = 1 ]; then echo "logs: $work"; else rm -rf "$work"; fi; }
trap cleanup EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
mkdir -p "$work/sv1/data" "$work/sv2/data" "$work/c/data"
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv1" +set port 26971 +set sv_public 0 +set bot_number 0 +set minplayers 0 +map $a +defer 10 "changelevel $b" >"$work/sv1.log" 2>&1 & pids+=("$!")
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv2" +set port 26972 +set sv_public 0 +set bot_number 0 +set minplayers 0 +map $c >"$work/sv2.log" 2>&1 & pids+=("$!")
for _ in $(seq 1 300); do grep -q 'Server spawned' "$work/sv1.log" && grep -q 'Server spawned' "$work/sv2.log" && break; sleep .1; done
cat > "$work/c/data/t.cfg" <<CFG
set touch_profile_done 1
set _cl_name JoinSeq
set vid_touchscreen 1
set fe_echo 1
fe attach 1
connect 127.0.0.1:26971
defer 20 "echo MARK-SECOND-JOIN; connect 127.0.0.1:26972"
defer 70 quit
CFG
taskset -c "${JOINSEQ_CPUS:-4-7}" "$engine" -xonotic -basedir "$BASEDIR" -userdir "$work/c" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 +exec t.cfg >"$work/c.log" 2>&1 & pids+=("$!")
# done when the second join reached the game (or give up after 80 s)
for _ in $(seq 1 160); do
  awk '/^MARK-SECOND-JOIN/{m=1} m && /^FE> app .*"state":"ingame".*"map":"'$c'"/{f=1} END{exit !f}' "$work/c.log" && break; sleep .5
done
sleep 1.5
python3 -I "$root/tests/touch-joinseq/check.py" "$work/c.log" "$a" "$b" "$c"
