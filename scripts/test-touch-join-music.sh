#!/usr/bin/env bash
# The menu theme (Rising of the Phoenix) never starts or continues in a join, remote or local;
# the map's own track starts during the join, before the first world frame, and again at a map
# change. Needs a BASEDIR with the music pack. (tests/touch-joinseq/music_check.py)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR}"
engine=${ENGINE:-$root/build/bin/xonotic}
work=$(mktemp -d); pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; if [ "${KEEP_JOINSEQ:-0}" = 1 ]; then echo "logs: $work"; else rm -rf "$work"; fi; }
trap cleanup EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
cc -std=c99 -Wall -Wextra -Werror -I"$root/engine/darkplaces" "$root/tests/touch-music/policy.c" -o "$work/policy" && "$work/policy"
client() { # dir joincommand quit
	mkdir -p "$1/data"
	cat > "$1/data/t.cfg" <<CFG
set touch_profile_done 1
set _cl_name MusicTest
set vid_touchscreen 1
set fe_echo 1
fe attach 1
set bgmvolume 1
defer 7 "echo MARK-JOIN; $2"
defer $3 quit
CFG
	taskset -c "${JOINSEQ_CPUS:-4-7}" timeout 90 "$engine" -xonotic -basedir "$BASEDIR" -userdir "$1" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 +exec t.cfg >"$1/log" 2>&1 || true
}
mkdir -p "$work/sv/data"
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port 26981 +set sv_public 0 +set bot_number 0 +set minplayers 0 +map stormkeep +defer 13 "changelevel boil" >"$work/sv.log" 2>&1 & pids+=("$!")
for _ in $(seq 1 300); do grep -q 'Server spawned' "$work/sv.log" && break; sleep .1; done
client "$work/remote" "connect 127.0.0.1:26981" 28
rc=0
python3 -I "$root/tests/touch-joinseq/music_check.py" "$work/remote/log" remote || rc=1
client "$work/local" "map atelier" 22
python3 -I "$root/tests/touch-joinseq/music_check.py" "$work/local/log" local || rc=1
exit $rc
