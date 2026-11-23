#!/bin/bash
# The free orbit camera (engine/darkplaces/touch_orbit.c): unit checks of its
# logic, then scripted fingers and mouse in an offscreen match against a local
# dedicated server, on this repository's own CSQC and on STOCK Xonotic game
# code (what a public server runs).
#
#   unit    touch_orbit_logic.h: contexts, pinch, wheel, easing, bounds, vote rule.
#   ours    touch client, our CSQC: a scripted death (pinch zooms and engages the
#           chase, a drag turns it, a tap on RESPAWN still respawns and the
#           player's cl_eventchase_* come back), then the match end (drag, pinch)
#           and the map vote (a drag from empty space turns the view and casts no
#           vote, a pinch zooms, a tap on an entry still votes, the tap moves
#           nothing).
#   stock   the same on a stock-code server.
#   desk    mouse and wheel (no touch controls): wheel zoom after death and at
#           the match end, a drag from empty space on the vote turns the view.
#
#   BASEDIR=<Godnotic basedir> STOCKBASE=<stock Xonotic basedir> \
#       scripts/test-touch-orbit.sh [unit] [ours] [stock] [desk]
# ENGINE (default engine/darkplaces/darkplaces-sdl), CPUS, OUT as the other
# live scripts. Exit 77: skipped.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
BASEDIR="${BASEDIR:-}"
STOCKBASE="${STOCKBASE:-}"
CPUS="${CPUS:-12-15}"
T="$ROOT/tests/touch-orbit"
WHICH="${*:-unit ours stock desk}"
fail=0
has() { [[ " $WHICH " == *" $1 "* ]]; }

if has unit; then
	out="$(mktemp -d)"
	cc -std=c99 -Wall -Wextra -Werror -I"$ROOT/engine/darkplaces" -o "$out/orbit_test" "$T/orbit_test.c" -lm || exit 1
	"$out/orbit_test" || fail=1
	rm -rf "$out"
fi
has ours || has stock || has desk || { [ $fail = 0 ] && exit 0 || exit 1; }

[ -x "$ENGINE" ] && command -v sdl2-config > /dev/null || { echo "SKIP: set ENGINE; SDL2 headers are needed for the finger shim"; exit 77; }
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-orbit.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	if [ -n "${OUT:-}" ]; then mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; fi
	rm -rf "$WORK"
}
trap cleanup EXIT
cc -shared -fPIC -O2 -U_GNU_SOURCE -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"

# the state at <t>, and a screenshot of the same frame (orbit-<kind>-<name>.png under screenshots/)
mark() { echo "$1 cmd echo MARK $2"; echo "$(awk "BEGIN{print $1 + 0.05}") cmd touch_orbit_state"; echo "$(awk "BEGIN{print $1 + 0.1}") cmd screenshot orbit-$KIND-$2.png"; }
own() { echo "$1 cmd prvm_globalget client mv_ownvote _o; wait; echo OWN=\$_o"; }

# events <kind> <phase>: the script for one run, times in seconds after xt_go.
# phases: dead (die, pinch, drag, respawn), inter (end of match scoreboard), vote (map vote)
events() {
	local kind=$1 phase=$2 t p k
	KIND=$1
	echo "1 cmd touch_join_cancel
1.5 cmd togglemenu 0
3 cmd join
3.5 cmd togglemenu 0
5 cmd set rcon_password orbit
5.5 cmd echo VM mouse=\$vid_mouse_confirmed kb=\$vid_keyboard_detected owns=\$touch_look_owns_view ts=\$vid_touchscreen ml=\$vid_touchscreen_mouselook sens=\$touch_sens_base"
	case $phase in
	dead)
		echo "7 cmd kill"
		mark 9.2 dead0
		if [ "$kind" = touch ]; then
			echo "9.8 glide 1 0.45 0.5 0.35 0.5 1.5 up
9.8 glide 2 0.55 0.5 0.65 0.5 1.5 up"
			mark 11.5 dead_pinch_out
			echo "12 glide 3 0.35 0.5 0.49 0.5 1.5 up
12 glide 4 0.65 0.5 0.51 0.5 1.5 up"
			mark 14 dead_pinch_in
			mark 14.5 dead_drag0
			echo "15 glide 5 0.62 0.30 0.85 0.30 1.5 up"
			mark 17.5 dead_drag1
		else
			echo "10 wheel 1
10.1 wheel 1
10.2 wheel 1"
			mark 11.5 dead_wheel
		fi
		echo "18 cmd echo MARK dead_cvars"
		echo "18.05 cmd echo CVARS \$cl_eventchase_death \$cl_eventchase_distance"
		echo "19 fdown 6 0.855 0.885
19.3 fup 6 0.855 0.885
19.5 cmd +jump
19.7 cmd -jump"
		mark 22 alive
		if [ "$kind" = touch ]; then
			mark 23 alive_drag0
			echo "23.5 glide 20 0.62 0.30 0.85 0.30 1.5 up"
			mark 25.5 alive_drag1
		fi
		echo "26.5 cmd echo MARK alive_cvars"
		echo "26.55 cmd echo CVARS \$cl_eventchase_death \$cl_eventchase_distance"
		echo "28 cmd quit";;
	inter)
		echo "7 cmd rcon set _endmatch 1"
		mark 11 inter0
		if [ "$kind" = touch ]; then
			echo "12 glide 7 0.30 0.50 0.60 0.50 1.5 up"
			mark 14 inter_drag
			echo "14.5 glide 8 0.45 0.5 0.35 0.5 1.5 up
14.5 glide 9 0.55 0.5 0.65 0.5 1.5 up"
			mark 16.5 inter_pinch
		else
			echo "12 wheel 1
12.1 wheel 1
12.2 wheel 1"
			mark 14 inter_wheel
		fi
		echo "19 cmd quit";;
	vote)
		echo "7 cmd rcon set _endmatch 1
12 cmd +attack
12.3 cmd -attack"
		for t in 13 14 15; do echo "$t cmd touch_vote_state"; done
		mark 16 vote0
		own 16.2
		if [ "$kind" = touch ]; then
			echo "17 glide 10 0.03 0.40 0.03 0.70 1.5 up"
			mark 19 vote_drag
			echo "19.5 glide 11 0.04 0.35 0.04 0.20 1.5 up
19.5 glide 12 0.04 0.55 0.04 0.80 1.5 up"
			mark 21.5 vote_pinch
		else
			echo "17 mmove 60 600
17.1 mdown"
			for i in 1 2 3 4 5 6 7 8; do echo "17.$((i + 1)) mmove $((60 + i * 30)) $((600 + i * 28))"; done
			echo "18 mup"
			mark 19 vote_drag
		fi
		own 21.7
		t=23 k=0
		for p in "0.50 0.45" "0.25 0.45" "0.75 0.45" "0.50 0.65"; do
			k=$((k + 1))
			if [ "$kind" = touch ]; then
				echo "$t fdown $((12 + k)) $p
$(awk "BEGIN{print $t + 0.3}") fup $((12 + k)) $p"
			else
				set -- $p
				echo "$t mmove $(awk "BEGIN{print int($1 * 1920)}") $(awk "BEGIN{print int($2 * 1200)}")
$(awk "BEGIN{print $t + 0.1}") mdown
$(awk "BEGIN{print $t + 0.3}") mup"
			fi
			mark "$(awk "BEGIN{print $t + 0.6}")" vote_tap$k
			own "$(awk "BEGIN{print $t + 0.7}")"
			t=$((t + 2))
		done
		echo "34 cmd quit";;
	esac
}

# run <name> <kind> <basedir> <phase>
run() {
	local name=$1 kind=$2 base=$3 phase=$4 port d="$WORK/$1" touch=1
	port="$(python3 -c 'import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
	mkdir -p "$d/srv" "$d/data"
	taskset -c "$CPUS" "$ENGINE" -dedicated -xonotic -basedir "$base" -userdir "$d/srv" +set port "$port" +set sv_public 0 +set rcon_secure 0 \
		+set rcon_password orbit +set g_warmup 0 +set bot_number 3 +set skill 1 +set g_respawn_delay 0 +set g_respawn_delay_max 0 \
		+set g_maplist_votable 4 +set g_maplist_votable_timeout 60 +set sv_mapchange_delay 4 +set timelimit_override 0 +map stormkeep > "$d/server.log" 2>&1 &
	local sp=$! n
	for n in $(seq 1 300); do grep -q "Server spawned" "$d/server.log" 2> /dev/null && break; sleep 0.2; done
	events "$kind" "$phase" > "$d/events.txt"
	[ "$kind" = desk ] && touch=0
	cat > "$d/data/tour.cfg" <<CFG
set developer 1
set cl_allow_uid2name 0
set touch_join_dialog_auto 0
set cl_welcome 0
set xt_go 0
set rcon_password orbit
set rcon_secure 0
${EXTRA_CFG:-}
connect 127.0.0.1:$port
defer 12 "set xt_go 1"
CFG
	LD_PRELOAD="$WORK/finj.so" XT_INJECT="$d/events.txt" timeout 160 taskset -c "$CPUS" "$ENGINE" -xonotic -basedir "$base" -userdir "$d" \
		+vid_fullscreen 0 +set vid_width 1920 +set vid_height 1200 +set vid_touchscreen $touch +set vid_touchscreen_detected 1 \
		+set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 \
		+set touch_profile_done 1 +set touch_setup_done 1 +set _touch_assets_ready 1 +set touch_always_autojoin 0 +set cl_touch_practice_pilot 0 \
		+set cl_touch_autoshoot 0 +set cl_touch_aimassist 0 +exec tour.cfg > "$d/client.log" 2>&1 || { tail -30 "$d/client.log"; echo "FAIL: client $name"; fail=1; }
	kill "$sp" 2> /dev/null; wait "$sp" 2> /dev/null
	echo "== $name"
	python3 "$T/check_live.py" "$kind" "$phase" "$d/client.log" || fail=1
}

PHASES="${PHASES:-dead inter vote}"
for what in ours stock desk; do
	has $what || continue
	kind=touch base="$BASEDIR"
	[ $what = desk ] && kind=desk
	[ $what = stock ] && base="$STOCKBASE"
	[ -d "$base/data" ] || { echo "SKIP $what: no basedir (BASEDIR / STOCKBASE)"; continue; }
	for ph in $PHASES; do run "$what-$ph" $kind "$base" $ph; done
done
[ $fail = 0 ] && echo "touch orbit: all checks passed" || { echo "touch orbit: FAILED"; exit 1; }
