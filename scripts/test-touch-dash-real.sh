#!/bin/bash
# The smart DASH on a REAL public server (from the master list), headless: a real match with real
# physics and ping. The first tap on the engine HUD's DASH button must act, and weapon boosts
# (forced up and forward) must fire and land where predicted.
#
#   BASEDIR=<Godnotic basedir> ADDR=<ip:port of a public server running stock maps> \
#       [LIBS=...] [CPUS=...] [OUT=<dir>] [SHOTDIR=<dir>] scripts/test-touch-dash-real.sh
# Uses the network (a few MB: the server's CSQC). Exit 77: skipped.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
T="$ROOT/tests/touch-dash"
[ -x "$ENGINE" ] && [ -d "${BASEDIR:-/x}/data" ] && [ -n "${ADDR:-}" ] || { echo "SKIP: set BASEDIR and ADDR"; exit 77; }
command -v sdl2-config > /dev/null || { echo "SKIP: sdl2-config"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}" SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
RUN=(); [ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-dashreal.XXXXXX")"
cleanup() { if [ -n "${OUT:-}" ]; then mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; fi; rm -rf "$WORK"; }
trap cleanup EXIT
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
{
	echo "0 cmd touch_join auto"; echo "0.3 cmd togglemenu 0"; echo "1.5 cmd touch_join auto"; echo "1.8 cmd togglemenu 0"
	echo "3 cmd touch_dash_status"
	echo "6 cmd echo MARK tap1"; echo "6.1 fdown 1 0.165 0.480"; echo "6.25 fup 1 0.165 0.480"; echo "6.5 cmd screenshot shots/real_tap.png"
	t=12
	for spec in "up blaster" "fwd blaster" "up devastator" "fwd devastator"; do
		case $spec in fwd*) echo "$t cmd set cl_yawspeed 180"; echo "$t.1 cmd +left"; echo "$((t + 1)).2 cmd -left"; t=$((t + 3));; esac
		echo "$t cmd echo MARK boost $spec"; echo "$t.1 cmd touch_dash $spec"; echo "$((t + 3)) cmd screenshot shots/real_$(echo $spec | tr ' ' _).png"
		t=$((t + 9))
	done
	echo "$t cmd touch_dash_status"; echo "$((t + 1)) cmd quit"
} > "$WORK/case_real.txt"
d="$WORK/real"; mkdir -p "$d/data"
printf '%s\n' "set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0; set cl_touch_ledgeguard 0; set cl_touch_autododge 0; set cl_touch_autoswitch 0; set cl_welcome 0; set xt_go 0; set cl_touch_dash_debug 1; set touch_dodge_visible 1; set cl_touch_dash_minhealth 5; set net_slist_seed 0; connect $ADDR; defer 50 \"set xt_go 1\"" | tr ';' '\n' > "$d/data/tour.cfg"
LD_PRELOAD="$WORK/finj.so" XT_INJECT="$WORK/case_real.txt" timeout -k 5 ${TMO:-220} "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
	+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 \
	+set _touch_assets_ready 1 +set touch_setup_done 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 \
	+exec tour.cfg > "$d/client.log" 2>&1
python3 "$T/check_live.py" real "$d/client.log"; rc=$?
[ -n "${SHOTDIR:-}" ] && mkdir -p "$SHOTDIR" && cp "$d"/data/shots/*.png "$SHOTDIR/" 2> /dev/null
exit $rc
