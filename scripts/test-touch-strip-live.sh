#!/bin/bash
# The weapon strip's HOOK entry and NADE row with fingers, in a running game (engine HUD on a
# dedicated server that runs STOCK game code, offscreen): a finger on the NADE row never
# hooks, on the HOOK entry never throws, the gap and a waiting NADE row take the finger and do
# nothing, and two fingers on the two rows do not mix. Fingers go through the real finger table
# (_touch_test_strip). Screens: build/touch-strip-live-*.png.
#
#   BASEDIR=<basedir with the clone's game code> STOCKBASE=<stock-data basedir> scripts/test-touch-strip-live.sh
# Exit 77: skipped (no engine or data).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
BASEDIR="${BASEDIR:-$ROOT/.suite-base}"; STOCKBASE="${STOCKBASE:-$ROOT/.stock-base}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] && [ -d "$STOCKBASE/data" ] || { echo "SKIP: set ENGINE, BASEDIR and STOCKBASE"; exit 77; }
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
RUN=(); [ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/strip-live.XXXXXX")"; trap 'jobs -p | xargs -r kill 2>/dev/null; rm -rf "$WORK"' EXIT
mkdir -p "$ROOT/build"
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || exit 1
QUIET='set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0; set cl_touch_ledgeguard 0; set cl_touch_autododge 0; set cl_welcome 0; set touch_always_autojoin 0; set cl_touch_nade_autothrow 0; set cl_touch_hook_rescue 0; set cl_touch_nade_refire 20'
fail=0
run() { # name "server cvars" tour.txt
  local n=$1 port=$((26700 + RANDOM % 250)) d="$WORK/$1"
  mkdir -p "$d/srv" "$d/cl/data"
  "${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$STOCKBASE" -userdir "$d/srv" +set port $port +set sv_public 0 +set bot_number 0 +set skill 1 \
    +set g_warmup 0 +set g_dm 1 +set g_balance_teams 0 +set sv_cheats 1 $2 +map stormkeep > "$d/srv.log" 2>&1 &
  local i; for i in $(seq 1 600); do grep -q "Server spawned" "$d/srv.log" 2>/dev/null && break; sleep 0.1; done; sleep 2
  { echo "$QUIET"; echo "connect 127.0.0.1:$port"; echo 'defer 40 "set xt_go 1"'; } | tr ';' '\n' > "$d/cl/data/tour.cfg"
  cp "$3" "$d/tour.txt"
  LD_PRELOAD="$WORK/finj.so" XT_INJECT="$d/tour.txt" timeout -k 5 120 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d/cl" +vid_fullscreen 0 +vid_width 1920 +vid_height 1200 \
    +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 +set _touch_assets_ready 1 \
    +set touch_setup_done 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 +scr_screenshot_jpeg 0 +scr_screenshot_png 1 +scr_screenshot_timestamp 0 +exec tour.cfg > "$d/client.log" 2>&1
  kill %1 2>/dev/null; wait 2>/dev/null
  for f in $(find "$d/cl" -name 'live-*.png'); do cp "$f" "$ROOT/build/touch-strip-$(basename "$f")"; done
  cp "$d/client.log" "$WORK/$1.log"
}
T="$WORK/nades.txt"; t=0
step() { echo "$1 cmd $2" >> "$T"; }
press() { # t slot band frac label : press, report, release
  step "$1" "_touch_test_strip $2 $3 $4"
  step "$(echo "$1 + 0.5" | bc)" "echo MARK $5"
  step "$(echo "$1 + 0.55" | bc)" "touch_hook_status"
  step "$(echo "$1 + 0.6" | bc)" "touch_nade_status"
  step "$(echo "$1 + 0.9" | bc)" "_touch_test_finger $2 0"
}
{
  echo "0 cmd touch_join spectate"; echo "0.2 cmd togglemenu 0"; echo "4 cmd touch_join auto"; echo "6 cmd cmd give all"
  echo "8 cmd _touch_test_strip"
} > "$T"
press 9 0 nade 0.5 nade_centre
press 11 0 gap 0.5 gap_dead
step 13 "echo MARK waiting_state"; step 13.05 "touch_nade_status"
press 14 0 nade 0.02 waiting_nade_top
press 16 0 nade 0.98 waiting_nade_bottom
press 18 0 hook 0.5 hook_centre
press 20 0 hook 0.02 hook_top
press 22 0 weapon3 0.5 weapon3
step 24 "_touch_test_strip 0 hook 0.5"; step 24.5 "_touch_test_strip 1 nade 0.5"; step 25 "echo MARK both_hook_first"; step 25.05 "touch_hook_status"; step 25.1 "touch_nade_status"
step 25.5 "screenshot live-hook-held.png"; step 26 "_touch_test_finger 0 0"; step 26 "_touch_test_finger 1 0"
step 33 "_touch_test_strip 0 nade 0.5"; step 33.5 "_touch_test_strip 1 hook 0.5"; step 34 "echo MARK both_nade_first"; step 34.05 "touch_hook_status"; step 34.1 "touch_nade_status"
step 34.5 "screenshot live-nade-held.png"; step 35 "_touch_test_finger 0 0"; step 35 "_touch_test_finger 1 0"
step 36.5 "screenshot live-waiting.png"
step 37.5 "quit"
sort -n -s -k1,1 "$T" -o "$T"
run nades '+set g_nades 1' "$T"
python3 - "$WORK/nades.log" <<'PY' || fail=1
import re, sys
log = open(sys.argv[1], errors='replace').read().splitlines()
def seg(mark):
    out = []; on = False
    for l in log:
        if l.startswith('MARK ' + mark): on = True; continue
        if on:
            if l.startswith('MARK '): break
            out.append(l)
    return '\n'.join(out)
def held(mark):
    s = seg(mark)
    h = re.search(r'touch hook: avail (\d) held (\d)', s); n = re.search(r'touch nade: .*?state (\d).*?held (\d)', s)
    return (int(h.group(2)) if h else None, int(n.group(2)) if n else None, int(n.group(1)) if n else None, int(h.group(1)) if h else None)
bad = 0
def check(ok, what):
    global bad
    print(('ok   ' if ok else 'FAIL ') + what)
    if not ok: bad += 1
strip = [l for l in log if l.startswith('STRIP')]
check(bool(strip) and 'hook=1' in strip[0] and 'nade=1' in strip[0], 'the strip has the HOOK entry and the NADE row: ' + (strip[0][:150] if strip else 'no STRIP line'))
hk, nd, st, av = held('nade_centre')
check(nd == 1 and hk == 0, 'a finger on the NADE row holds the grenade and never the hook (nade %s, hook %s)' % (nd, hk))
hk, nd, st, av = held('gap_dead')
check(nd == 0 and hk == 0, 'the gap takes the finger and does nothing (nade %s, hook %s)' % (nd, hk))
for m in ('waiting_nade_top', 'waiting_nade_bottom'):
    hk, nd, st, av = held(m)
    check(nd == 0 and hk == 0 and st == 3, '%s: a waiting NADE row swallows the finger, no hook (nade %s, hook %s, state %s)' % (m, nd, hk, st))
hk, nd, st, av = held('hook_centre')
check(hk == 1 and nd == 0, 'a finger on the HOOK entry holds the hook and never the grenade (hook %s, nade %s)' % (hk, nd))
hk, nd, st, av = held('hook_top')
check(hk == 1 and nd == 0, 'the HOOK entry top edge is the hook (hook %s, nade %s)' % (hk, nd))
hk, nd, st, av = held('weapon3')
check(hk == 0 and nd == 0, 'a weapon row neither hooks nor throws (hook %s, nade %s)' % (hk, nd))
hk, nd, st, av = held('both_hook_first')
check(hk == 1 and nd == 0, 'hook held first: a finger on NADE is ignored (hook %s, nade %s)' % (hk, nd))
hk, nd, st, av = held('both_nade_first')
check(nd == 1 and hk == 0, 'nade held first: a finger on HOOK is ignored (hook %s, nade %s)' % (hk, nd))
sys.exit(1 if bad else 0)
PY
echo "screens: build/touch-strip-live-*.png"
exit $fail
