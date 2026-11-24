#!/usr/bin/env bash
# Exercise CHAT -> LOG -> close with a draft, team mode and caret preserved.
# BASEDIR=<game data dir> ENGINE=<SDL binary> QCC=<gmqcc> bash scripts/test-chat-composer.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}
QCC=${QCC:-$ROOT/engine/gmqcc/gmqcc}
: "${BASEDIR:?set BASEDIR}"
[ -x "$ENGINE" ] && [ -x "$QCC" ] && [ -d "$BASEDIR/data" ] || { echo 'SKIP: ENGINE, QCC or BASEDIR missing'; exit 77; }
WORK=$(mktemp -d "${TMPDIR:-/tmp}/godnotic-chat-composer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/data" "$WORK/qc"
QC="$ROOT/engine/data/xonotic-data.pk3dir/qcsrc"
# qcc.sh expects WORKDIR relative to qcsrc; place all generated files in WORK.
REL=$(realpath --relative-to="$QC" "$WORK/qc")
make -C "$QC" QCC="$QCC" XON_BUILDSYSTEM=1 QCCFLAGS_WATERMARK=chat-test \
  PROGS_OUT="$WORK/data" WORKDIR="$REL" "$WORK/data/csprogs.dat" > "$WORK/build.log" 2>&1
# A test-only initial draft avoids reliance on a physical keyboard or Android IME.
# The flag is cleared before opening LOG, so reopening cannot seed it a second time.
python3 - "$WORK/qc/client.qc" "$WORK/qc/client-test.qc" <<'PY'
import sys
s = open(sys.argv[1]).read()
a = 'void Touch_Chat_Open(float team)\n{'
b = a + '\n if (cvar("_chat_test_seed") && !touch_chat_has_saved) { touch_chat_saved = strzone("draft"); touch_chat_saved_caret = 3; touch_chat_saved_team = team; touch_chat_has_saved = true; }'
assert s.count(a) == 1
open(sys.argv[2], 'w').write(s.replace(a, b))
PY
"$QCC" -std=gmqcc -O3 -Werror -Wall -Wno-field-redeclared -futf8 -freturn-assignments -frelaxed-switch -Ooverlap-locals -flno -fno-bail-on-werror -o "$WORK/data/csprogs.dat" "$WORK/qc/client-test.qc" > "$WORK/qcc.log" 2>&1
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl
cat > "$WORK/data/autoexec.cfg" <<'CFG'
seta touch_profile_done 1
seta touch_setup_done 1
seta _cl_name Tester
CFG
cat > "$WORK/events" <<'EVENTS'
0 cmd touch_join join
0.5 cmd cmd join
1.5 cmd set _chat_test_seed 1
2 cmd touch_chat team
3 cmd set _chat_test_seed 0
3.5 cmd _touch_sheet_dbg
4 fdown 1 0.790 0.105
4.2 fup 1 0.790 0.105
5.5 cmd touch_chatlog_state
6 fdown 1 0.960 0.263
6.2 fup 1 0.960 0.263
8 cmd _touch_sheet_dbg
8.5 cmd screenshot restored.png
9 cmd quit
EVENTS
SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy LD_PRELOAD="$WORK/finj.so" XT_INJECT="$WORK/events" \
 timeout 90 "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$WORK" +vid_fullscreen 0 +vid_width 1280 +vid_height 720 \
 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set vid_touchscreen_xdpi 160 +set vid_touchscreen_ydpi 160 \
 +set _touch_assets_ready 1 +set bot_number 0 +map stormkeep +defer 15 'set xt_go 1' > "$WORK/game.log" 2>&1
# Two observations: before opening the log and after closing it.
[ "$(grep -c 'open=1 text=draft caret=3' "$WORK/game.log")" -eq 2 ]
grep -q 'CHATLOG-STATE .*panel=1' "$WORK/game.log"
[ -n "${OUT:-}" ] && { mkdir -p "$OUT"; cp "$WORK/game.log" "$WORK/data/restored.png" "$OUT/"; }
echo 'chat composer draft round trip: OK'
