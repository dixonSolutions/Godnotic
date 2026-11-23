#!/bin/bash
# The smart DASH against a real dedicated server, offscreen: does a weapon boost land the player
# where it predicted (Blaster and Devastator, up and forward), is dodging on or off read from
# the server, does a plain dash dodge, and is a boost refused under the health floor.
# The client is driven through the console by the finger shim (tests/touch-controls/finj.c).
#
#   BASEDIR=<Godnotic basedir with this clone's pk3dir, QuakeC compiled> [ENGINE=...] [LIBS=...] \
#       [CPUS=<taskset list>] [OUT=<dir to keep logs>] scripts/test-touch-dash-live.sh
# Exit 77: skipped (no engine, data or SDL headers).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
BASEDIR="${BASEDIR:-}"
T="$ROOT/tests/touch-dash"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || { echo "SKIP: set ENGINE and BASEDIR"; exit 77; }
command -v sdl2-config > /dev/null || { echo "SKIP: sdl2-config (SDL2 headers) is needed to build the shim"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
RUN=()
[ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-dash.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	if [ -n "${OUT:-}" ]; then mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; fi
	rm -rf "$WORK"
}
trap cleanup EXIT
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
fail=0
MAP="${MAP:-dance}"

QUIET='set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0; set cl_touch_ledgeguard 0; set cl_touch_autododge 0; set cl_touch_autoswitch 0; set cl_welcome 0; set xt_go 0; set cl_touch_dash_debug 1'

server() { # name port extra-server-args...
	local d="$WORK/$1" port=$2; shift 2
	mkdir -p "$d"
	"${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$d" +set port "$port" +set sv_public 0 \
		+set bot_number 0 +set g_warmup 0 +set g_dm 1 +set timelimit_override 0 +set fraglimit_override 0 \
		+set g_weaponarena "devastator blaster" +set g_balance_selfdamagepercent 0.2 +set g_start_ammo_rockets 100 \
		+set rcon_password xtctl "$@" +map "$MAP" > "$d/server.log" 2>&1 &
	local n; for n in $(seq 1 900); do grep -q "Server spawned" "$d/server.log" 2> /dev/null && break; sleep 0.1; done
	sleep 2
}

client() { # name inject.txt "console text" timeout
	local d="$WORK/$1"
	mkdir -p "$d/data"
	printf '%s\n' "$3" | tr ';' '\n' > "$d/data/tour.cfg"
	LD_PRELOAD="$WORK/finj.so" XT_INJECT="$2" timeout -k 5 "$4" "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
		+vid_fullscreen 0 +vid_width 1280 +vid_height 720 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
		+set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 +set _touch_assets_ready 1 +set touch_setup_done 1 \
		+set touch_profile_done 1 +set cl_allow_uid2name 0 +exec tour.cfg > "$d/client.log" 2>&1
}

port() { echo $((27400 + RANDOM % 250)); }
join() { echo "set touch_always_autojoin 1; set touch_autojoin_team auto; connect 127.0.0.1:$1; defer 35 \"set xt_go 1\""; }

# The boosts: each forced direction and weapon, nine seconds apart, from time $2 on.
boost_lines() {
	local t=$1 spec
	for spec in "up blaster" "fwd blaster" "up devastator" "fwd devastator" "up blaster" "fwd devastator"; do
		# a forward boost faces the other way each time, so the run stays on the map
		case $spec in fwd*) echo "$t cmd set cl_yawspeed 180"; echo "$t.1 cmd +left"; echo "$((t + 1)).2 cmd -left"; t=$((t + 3));; esac
		echo "$t cmd echo MARK boost $spec"
		echo "$t.1 cmd touch_dash $spec"
		t=$((t + 9))
	done
	echo "$t cmd touch_dash_status"
	echo "$((t + 1)) cmd quit"
}

run_case() { # name server-args... -- client-extra
	local name=$1; shift
	local sargs=() cextra=""
	while [ $# -gt 0 ] && [ "$1" != "--" ]; do sargs+=("$1"); shift; done
	[ "${1:-}" = "--" ] && shift
	cextra="$*"
	local p; p=$(port)
	server "srv_$name" $p "${sargs[@]}"
	client "$name" "$WORK/case_$name.txt" "$QUIET; $cextra; $(join $p)" 160
	kill %1 2> /dev/null; wait 2> /dev/null
}

want() { case " ${CASES:-on off floor ui qc} " in *" $1 "*) return 0;; esac; return 1; }

# 1. dodging on: the server's mutator is read; a plain dash dodges; boosts land where predicted
want on && {
	echo "0 cmd touch_dash_status"
	echo "1 cmd echo MARK dash"
	echo "1.1 cmd +forward"
	echo "1.4 cmd touch_dash"
	echo "2.5 cmd -forward"
	echo "3 cmd touch_dash_status"
	boost_lines 6
} > "$WORK/case_on.txt"
want on && { run_case on +set g_dodging 1 -- "set cl_touch_dash_dir 0; set cl_touch_dash_minhealth 5"
python3 "$T/check_live.py" on "$WORK/on/client.log" || fail=1; }

# 2. dodging off
want off && {
	echo "0 cmd touch_dash_status"
	# no dodging: two plain dashes that do nothing teach the client; the third tap boosts
	echo "1 cmd echo MARK dash1"
	echo "1.1 cmd +forward"
	echo "1.4 cmd touch_dash"
	echo "2.5 cmd -forward"
	echo "4 cmd echo MARK dash2"
	echo "4.1 cmd +forward"
	echo "4.4 cmd touch_dash"
	echo "5.5 cmd -forward"
	echo "8 cmd touch_dash_status"
	echo "9 cmd echo MARK auto"
	echo "9.1 cmd touch_dash"
	boost_lines 15
} > "$WORK/case_off.txt"
want off && { run_case off +set g_dodging 0 -- "set cl_touch_dash_dir 0; set cl_touch_dash_minhealth 5"
python3 "$T/check_live.py" off "$WORK/off/client.log" || fail=1; }

# 3. the health floor
want floor && {
	echo "0 cmd touch_dash_status"
	echo "1 cmd echo MARK refuse"
	echo "1.1 cmd touch_dash up blaster"
	echo "6 cmd echo MARK end"
	echo "7 cmd quit"
} > "$WORK/case_floor.txt"
want floor && { run_case floor +set g_dodging 0 -- "set cl_touch_dash_minhealth 500"
python3 "$T/check_live.py" floor "$WORK/floor/client.log" || fail=1; }


# 4. the engine HUD on a STOCK server (what every public server runs): DASH under the touch controls,
# its indicator, the long-press picker and picking from it; screenshots at 1920x1200.
if want ui; then
	STOCKBASE="${STOCKBASE:-$ROOT/.stock-base}"
	SHOTS="${SHOTS:-$WORK/shots}"; mkdir -p "$SHOTS"
	ui_client() { # name inject text
		local d="$WORK/$1"; mkdir -p "$d/data"
		printf '%s\n' "$3" | tr ';' '\n' > "$d/data/tour.cfg"
		LD_PRELOAD="$WORK/finj.so" XT_INJECT="$2" timeout -k 5 100 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
			+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
			+set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 +set _touch_assets_ready 1 +set touch_setup_done 1 \
			+set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 \
			+exec tour.cfg > "$d/client.log" 2>&1
	}
	ui_server() {
		local d="$WORK/srv_ui"; mkdir -p "$d"
		"${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$STOCKBASE" -userdir "$d" +set port $1 +set sv_public 0 +set bot_number 2 +set skill 1 \
			+set g_warmup 0 +set g_dm 1 +set rcon_password xtctl +set g_maplist dance +map dance > "$d/server.log" 2>&1 &
		local n; for n in $(seq 1 900); do grep -q "Server spawned" "$d/server.log" 2> /dev/null && break; sleep 0.1; done
		sleep 2
	}
	UIQ="$QUIET; set touch_dodge_visible 1; set touch_always_autojoin 1; set touch_autojoin_team auto"
	p=$(port); ui_server $p
	{
		echo "0 cmd touch_join auto"; echo "0.3 cmd togglemenu 0"; echo "1.5 cmd touch_join auto"; echo "1.8 cmd togglemenu 0"
		echo "2 cmd touch_dash_status"
		echo "3 cmd echo MARK idle"; echo "3.1 cmd screenshot shots/dash_idle.png"
		echo "5 cmd echo MARK press"; echo "5 fdown 1 0.165 0.480"
		echo "6.2 cmd echo MARK picker"; echo "6.3 cmd screenshot shots/dash_picker.png"
		echo "7 fup 1 0.165 0.480"
		echo "8 cmd echo MARK after"; echo "9 cmd touch_dash_status"
		echo "10 cmd quit"
	} > "$WORK/case_ui1.txt"
	ui_client ui1 "$WORK/case_ui1.txt" "$UIQ; connect 127.0.0.1:$p; defer 30 \"set xt_go 1\""
	python3 "$T/check_live.py" ui1 "$WORK/ui1/client.log" || fail=1
	# second run: slide onto the chips the first run printed and let go
	read -r UPX UPY < <(sed -n 's/.*picker chip UP at \([0-9.]*\) \([0-9.]*\).*/\1 \2/p' "$WORK/ui1/client.log" | head -1)
	read -r BLX BLY < <(sed -n 's/.*picker chip BLA at \([0-9.]*\) \([0-9.]*\).*/\1 \2/p' "$WORK/ui1/client.log" | head -1)
	if [ -n "${UPX:-}" ]; then
		{
			echo "0 cmd touch_join auto"; echo "0.3 cmd togglemenu 0"; echo "1.5 cmd touch_join auto"; echo "1.8 cmd togglemenu 0"
			echo "3 cmd echo MARK pickdir"; echo "3 fdown 1 0.165 0.480"
			echo "4 cmd echo MARK hold"; echo "4.5 fmove 1 $UPX $UPY"; echo "4.9 cmd screenshot shots/dash_picker_hover.png"
			echo "5.2 fup 1 $UPX $UPY"
			echo "6 cmd echo DIR \$cl_touch_dash_dir WEAPON [\$cl_touch_dash_weapon]"
			if [ -n "${BLX:-}" ]; then
				echo "7 fdown 1 0.165 0.480"; echo "8 fmove 1 $BLX $BLY"; echo "8.5 fup 1 $BLX $BLY"
				echo "9 cmd echo DIR \$cl_touch_dash_dir WEAPON [\$cl_touch_dash_weapon]"
			fi
			echo "9.5 cmd screenshot shots/dash_after_pick.png"
			echo "10 cmd quit"
		} > "$WORK/case_ui2.txt"
		ui_client ui2 "$WORK/case_ui2.txt" "$UIQ; connect 127.0.0.1:$p; defer 30 \"set xt_go 1\""
		python3 "$T/check_live.py" ui2 "$WORK/ui2/client.log" || fail=1
	else
		echo "FAIL the picker printed no chips"; fail=1
	fi
	kill %1 2> /dev/null; wait 2> /dev/null
	[ -n "${SHOTDIR:-}" ] && mkdir -p "$SHOTDIR" && cp "$WORK"/ui*/data/shots/*.png "$SHOTDIR/" 2> /dev/null
fi

# 5. the port's own CSQC HUD (a local game): its DASH button sends the same tap
if want qc; then
	{
		echo "0 cmd touch_join auto"; echo "0.3 cmd togglemenu 0"; echo "1.5 cmd touch_join auto"; echo "1.8 cmd togglemenu 0"
		echo "3 cmd echo MARK qc"; echo "3.1 fdown 1 0.165 0.480"; echo "3.25 fup 1 0.165 0.480"
		echo "5 cmd touch_dash_status"
		echo "6 cmd quit"
	} > "$WORK/case_qc.txt"
	client qc "$WORK/case_qc.txt" "$QUIET; set touch_dodge_visible 1; set g_dm 1; set g_warmup 0; set bot_number 1; set skill 1; map dance; defer 16 \"set xt_go 1\"" 100
	python3 "$T/check_live.py" qc "$WORK/qc/client.log" || fail=1
fi

echo "test-touch-dash-live: $([ $fail = 0 ] && echo all passed || echo FAILED)"
exit $fail
