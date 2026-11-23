#!/bin/bash
# Join a REAL public server from the master list with the headless desktop client (offscreen, no window) and report the
# weapon strip: HOOK entry / NADE row, hook and nade state, then (phase 2) real finger taps through the SDL event path
# (tests/touch-controls/finj.c) on weapon rows, the HOOK entry and the NADE row.
#   scripts/real-server-probe.sh <ip:port> <name> [tap]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"; BASEDIR="${BASEDIR:-$ROOT/.suite-base}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy; unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
RUN=(); [ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
SRV=$1; N=$2; MODE=${3:-look}
WORK="${TMPDIR:-/tmp}/real-$N"; rm -rf "$WORK"; mkdir -p "$WORK/cl/data" "$ROOT/build"
trap 'jobs -p | xargs -r kill 2>/dev/null' EXIT
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
QUIET='set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0; set cl_touch_ledgeguard 0; set cl_touch_autododge 0; set cl_welcome 0; set touch_always_autojoin 0; set cl_touch_aim_debug 0'
{ echo "$QUIET"; echo "connect $SRV"; echo 'defer 60 "set xt_go 1"'; } | tr ';' '\n' > "$WORK/cl/data/tour.cfg"
T="$WORK/tour.txt"
{
 echo "0 cmd echo MARK start"
 for t in 6 10 14 18; do echo "$t cmd touch_join spectate"; echo "$t.2 cmd togglemenu 0"; done
 echo "20 cmd touch_join auto"
 echo "26 cmd echo MARK joined"; echo "26.1 cmd touch_hud_state"; echo "26.2 cmd _touch_test_strip"; echo "26.3 cmd touch_hook_status"; echo "26.4 cmd touch_nade_status"
 echo "26.5 cmd screenshot real-$N-strip.png"
} > "$T"
if [ "$MODE" = tap ]; then
  read x w0y rowh < <(cat "$ROOT/build/real-$N.strip" 2>/dev/null)
  [ -z "${x:-}" ] && { echo "no strip coordinates (run look first)"; exit 1; }
  W=1920; H=1200
  px() { awk -v v="$1" -v s="$2" 'BEGIN{printf "%d", v*s}'; }
  X=$x
  # tap weapon rows 1 and 3 (second and fourth), each: down, up; screenshot after each, report held weapon
  # one real tap-and-hold on row 1 (the second weapon row, or the HOOK entry where the first row is the only weapon),
  # state reported while the finger is down and after it lifts, screenshots of both
  i=${ROW:-1}
  {
  Y=$(awk -v a="$w0y" -v r="$rowh" -v i=$i 'BEGIN{printf "%.4f", a+r*i}')
  echo "29 cmd echo MARK before"; echo "29.1 cmd touch_hud_state"; echo "29.15 cmd touch_hook_status"
  if [ -n "${SYNTH:-}" ]; then echo "30 cmd _touch_test_strip 0 hook 0.5"; else echo "30 fdown 1 $X $Y"; fi; echo "30.8 cmd echo MARK down"; echo "30.85 cmd touch_hook_status"; echo "30.9 cmd touch_nade_status"; echo "31 cmd screenshot real-$N-tapdown.png"
  if [ -n "${SYNTH:-}" ]; then echo "32 cmd _touch_test_finger 0 0"; else echo "32 fup 1"; fi; echo "32.8 cmd echo MARK up"; echo "32.85 cmd touch_hook_status"; echo "32.9 cmd touch_hud_state"; echo "33 cmd screenshot real-$N-tapup.png"
  } >> "$T"
fi
echo "45 cmd quit" >> "$T"
awk -v d="${D:-0}" '{ $1 = $1 + d; print }' "$T" | sort -n -s -k1,1 > "$T.s" && mv "$T.s" "$T"
LD_PRELOAD="$WORK/finj.so" XT_INJECT="$T" timeout -k 5 $((100 + ${D:-0})) "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$WORK/cl" +vid_fullscreen 0 +vid_width 1920 +vid_height 1200 \
  +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 +set _touch_assets_ready 1 \
  +set touch_setup_done 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 +exec tour.cfg > "$WORK/client.log" 2>&1
for f in $(find "$WORK/cl" -name "real-$N-*.png"); do cp "$f" "$ROOT/build/$(basename "$f")"; done
sed 's/\x1b\[[0-9;]*m//g' "$WORK/client.log" | grep -a '^STRIP\|touch hook:\|touch nade:\|^TOUCHHUD\|^TOUCHMENU\|Connection\|rror\|download\|signon\|connected' | tail -24 | cut -c1-230
sed 's/\x1b\[[0-9;]*m//g' "$WORK/client.log" | grep -a '^STRIP' | tail -1 | sed -E 's/.*x=([0-9.]+) w0y=([0-9.]+).*rowh=([0-9.]+).*/\1 \2 \3/' > "$ROOT/build/real-$N.strip"
