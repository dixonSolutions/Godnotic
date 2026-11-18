#!/bin/bash
# Exercise a real offscreen SDL resize after a local match has connected.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}
BASEDIR=${BASEDIR:?set BASEDIR to a Godnotic basedir with compiled game code and map assets}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/user/data"
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl
cat > "$WORK/inject" <<'EOF'
0 cmd echo ROTATE_BEFORE
0.1 cmd touch_hud_state rects
0.2 cmd vid_width
2 resize 360 640
4 cmd echo ROTATE_AFTER
4.1 cmd touch_hud_state rects
4.2 cmd vid_width
6 cmd echo ROTATE_DONE
6.2 cmd quit
EOF
cat > "$WORK/user/data/rotate.cfg" <<'EOF'
set _touch_assets_ready 1
set touch_setup_done 1
set touch_profile_done 1
set vid_touchscreen 1
set vid_touchscreen_detected 1
set bot_number 0
set g_warmup 0
set xt_go 0
map stormkeep
defer 25 "set xt_go 1"
EOF
if ! SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy LD_PRELOAD="$WORK/finj.so" XT_INJECT="$WORK/inject" timeout -k 5 120 "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$WORK/user" +vid_fullscreen 0 +vid_width 640 +vid_height 360 +exec rotate.cfg > "$WORK/log" 2>&1; then
	tail -80 "$WORK/log"
	exit 1
fi
python3 - "$WORK/log" <<'PY'
import re, sys
s = re.sub(r'\x1b\[[0-9;]*m', '', open(sys.argv[1], errors='replace').read())
try:
    assert s.count('Connection established to local:') == 1
    assert s.count('Server spawned.') == 1
    assert s.index('Connection established to local:') < s.index('ROTATE_BEFORE')
    before, rest = s.split('ROTATE_BEFORE', 1)[1].split('ROTATE_AFTER', 1)
    after, _ = rest.split('ROTATE_DONE', 1)
    assert 'Disconnected' not in before + after
    assert 'TOUCHHUD mode=' in before and 'TOUCHHUD mode=' in after
    def size(text):
        m = re.search(r'TOUCHRECTS mode=\S+ size=(\d+)x(\d+)', text)
        assert m
        return tuple(map(int, m.groups()))
    w1, h1 = size(before)
    w2, h2 = size(after)
    assert w1 > h1 and w2 < h2, (w1, h1, w2, h2)
    def console_rect(text):
        m = re.search(r'TOUCHRECT r console ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)', text)
        assert m
        return tuple(map(float, m.groups()))
    assert console_rect(before) != console_rect(after)
    assert re.search(r'vid_width is "640"', before)
    assert re.search(r'vid_width is "360"', after)
except (AssertionError, ValueError):
    print(s[-12000:])
    raise
print(f'SDL offscreen mid-match resize: PASS; HUD {w1}x{h1} -> {w2}x{h2}, connection retained')
PY
