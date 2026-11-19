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
waitlog() { # file pattern seconds
	for _ in $(seq 1 $(($3 * 2))); do grep -q "$2" "$1" && return 0; sleep .5; done
	return 1
}
client() { # dir joincommand: starts the client in the menu, waits for the theme, then joins; leaves it running
	mkdir -p "$1/data"
	cat > "$1/data/t.cfg" <<CFG
set touch_profile_done 1
set _cl_name MusicTest
set vid_touchscreen 1
set fe_echo 1
fe attach 1
alias xt_beat "fe beat; defer 5 xt_beat"
xt_beat
set bgmvolume 1
alias xt_poll "exec poll.cfg; defer 0.5 xt_poll"
defer 1 xt_poll
defer 120 quit
CFG
	: > "$1/data/poll.cfg"
	taskset -c "${JOINSEQ_CPUS:-4-7}" "$engine" -xonotic -basedir "$BASEDIR" -userdir "$1" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 +exec t.cfg >"$1/log" 2>&1 & pids+=("$!")
	waitlog "$1/log" 'MUSIC: start menu theme' 40 || echo "note: the menu theme never started"
	echo "alias xt_poll \"\"; echo MARK-JOIN; $2" > "$1/data/poll.cfg"
}
mkdir -p "$work/sv/data"
mkfifo "$work/sv.in"; exec 7<>"$work/sv.in"
"$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port 26981 +set sv_public 0 +set bot_number 0 +set minplayers 0 +map stormkeep <"$work/sv.in" >"$work/sv.log" 2>&1 & pids+=("$!")
waitlog "$work/sv.log" 'Server spawned' 30
rc=0
client "$work/remote" "connect 127.0.0.1:26981"
waitlog "$work/remote/log" '^FE> app .*"state":"ingame".*"map":"stormkeep"' 60 || rc=1
sleep 2; echo "changelevel boil" >&7
waitlog "$work/remote/log" '^FE> app .*"state":"ingame".*"map":"boil"' 60 || rc=1
sleep 2
python3 -I "$root/tests/touch-joinseq/music_check.py" "$work/remote/log" remote || rc=1
client "$work/local" "map atelier"
waitlog "$work/local/log" '^FE> app .*"state":"ingame".*"map":"atelier"' 60 || rc=1
sleep 2
python3 -I "$root/tests/touch-joinseq/music_check.py" "$work/local/log" local || rc=1
exit $rc
