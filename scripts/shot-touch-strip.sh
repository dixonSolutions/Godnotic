#!/bin/bash
# Headless screenshots of the touch weapon strip (engine HUD, stock server game code, no window):
# the HOOK entry and the NADE action row. Output: build/touch-strip-*.png
#   BASEDIR=<basedir with game data> STOCKBASE=<basedir with only stock data> scripts/shot-touch-strip.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
BASEDIR="${BASEDIR:-$ROOT/.suite-base}"; STOCKBASE="${STOCKBASE:-$ROOT/.stock-base}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
RUN=(); [ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
W=${W:-1920}; H=${H:-1200}; DPI=${DPI:-283}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/strip-shot.XXXXXX")"; trap 'jobs -p | xargs -r kill 2>/dev/null; rm -rf "$WORK"' EXIT
mkdir -p "$ROOT/build"
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
QUIET='set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0; set cl_touch_ledgeguard 0; set cl_touch_autododge 0; set cl_welcome 0; set touch_always_autojoin 0'
shot() { # name "server cvars" "client inject line" "client cvars"
  local n=$1 port=$((26700 + RANDOM % 250)) d="$WORK/$1"
  mkdir -p "$d/srv" "$d/cl/data"
  "${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$STOCKBASE" -userdir "$d/srv" +set port $port +set sv_public 0 +set bot_number 2 +set skill 1 \
    +set g_warmup 0 +set g_dm 1 +set g_balance_teams 0 $2 +map stormkeep > "$d/srv.log" 2>&1 &
  local i; for i in $(seq 1 600); do grep -q "Server spawned" "$d/srv.log" 2>/dev/null && break; sleep 0.1; done; sleep 2
  { echo "$QUIET"; echo "connect 127.0.0.1:$port"; echo 'defer 30 "set xt_go 1"'; } | tr ';' '\n' > "$d/cl/data/tour.cfg"
  { echo "0 cmd touch_join spectate"; echo "0.2 cmd togglemenu 0"; echo "4 cmd touch_join auto"; echo "$3"; echo "9 cmd touch_hook_status"; echo "9.1 cmd touch_nade_status"; echo "9.15 cmd echo ROW=\$_cl_touch_hook_row VIS=\$touch_hook_visible TS=\$vid_touchscreen ML=\$vid_touchscreen_mouselook"; echo "9.2 cmd screenshot strip-$n.png"; echo "11 cmd quit"; } > "$d/tour.txt"
  LD_PRELOAD="$WORK/finj.so" XT_INJECT="$d/tour.txt" timeout -k 5 90 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d/cl" +vid_fullscreen 0 +vid_width $W +vid_height $H \
    +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi $DPI +set vid_touchscreen_ydpi $DPI +set _touch_assets_ready 1 \
    +set touch_setup_done 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 $4 +exec tour.cfg > "$d/client.log" 2>&1
  kill %1 2>/dev/null; wait 2>/dev/null
  f=$(find "$d/cl" -name "strip-$n.png" | head -1); [ -n "$f" ] && cp "$f" "$ROOT/build/touch-strip-$n.png" && echo "saved build/touch-strip-$n.png" || echo "NO SHOT for $n (see $d/client.log)"
  grep -a "^ROW=" "$d/client.log" | tail -1
  grep -a "touch hook\|touch nade" "$d/client.log" | tail -4 | cut -c1-220
  grep -a '^TOUCHHUD' "$d/client.log" | tail -1 | cut -c1-120
}
[ "${ONLY:-}" = local ] || shot nade-hookweapon '+set g_nades 1 +set sv_cheats 1' "6 cmd cmd give all" ""
[ "${ONLY:-}" = local ] || shot offhand-hook '+set g_grappling_hook 1' "6 cmd echo -" ""

# no hook, no nades: the strip as it was; an old layout's touch_hook_visible 1 (hook entry on, value read as 2);
# touch_hook_visible 0 (no entry: the Hook weapon is a row of its own again)
[ "${ONLY:-}" = local ] || shot plain '' "6 cmd echo -" ""
[ "${ONLY:-}" = local ] || shot oldlayout-hook1 '+set g_nades 1 +set sv_cheats 1' "6 cmd cmd give all" "+seta touch_hook_visible 1 +seta touch_hook_x 0.3 +seta touch_hook_y 0.3 +seta touch_hook_size 0.2"
[ "${ONLY:-}" = local ] || shot oldlayout-hook0 '+set g_nades 1 +set sv_cheats 1' "6 cmd cmd give all" "+seta touch_hook_visible 0"
# The port's own CSQC HUD, in a local match (no server of ours): the same strip drawn by touch_hud.qc.
shotlocal() { # name "console text before map"
  local n=$1 d="$WORK/$1"
  mkdir -p "$d/cl/data"
  { echo "$QUIET"; echo "$2"; echo "set bot_number 2; set skill 1; set g_warmup 0; map stormkeep"; echo 'defer 30 "set xt_go 1"'; } | tr ';' '\n' > "$d/cl/data/tour.cfg"
  { echo "0 cmd touch_join spectate"; echo "0.2 cmd togglemenu 0"; echo "4 cmd touch_join auto"; echo "6.5 cmd cmd give all"; echo "9 cmd touch_hook_status"; echo "9.1 cmd touch_nade_status"; echo "9.2 cmd screenshot strip-$n.png"; echo "11 cmd quit"; } > "$d/tour.txt"
  LD_PRELOAD="$WORK/finj.so" XT_INJECT="$d/tour.txt" timeout -k 5 120 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d/cl" +vid_fullscreen 0 +vid_width $W +vid_height $H \
    +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi $DPI +set vid_touchscreen_ydpi $DPI +set _touch_assets_ready 1 \
    +set touch_setup_done 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 +exec tour.cfg > "$d/client.log" 2>&1
  f=$(find "$d/cl" -name "strip-$n.png" | head -1); [ -n "$f" ] && cp "$f" "$ROOT/build/touch-strip-$n.png" && echo "saved build/touch-strip-$n.png" || echo "NO SHOT for $n (see $d/client.log)"
  grep -a "touch hook\|touch nade" "$d/client.log" | tail -3 | cut -c1-200
}
shotlocal local-csqc "set sv_cheats 1; set g_nades 1"
shotlocal local-csqc-offhand "set sv_cheats 1; set g_grappling_hook 1"
