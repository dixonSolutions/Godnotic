#!/bin/bash
# Offscreen smoke test; set ENGINE, BASEDIR and OUT to retain screenshots.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
if [ ! -x "$ENGINE" ] || [ ! -d "${BASEDIR:-/nonexistent}/data" ]; then echo 'SKIP: set ENGINE and BASEDIR'; exit 0; fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-spec-inputs.XXXXXX")"
server_pid= client_pid=
cleanup() {
 [ -z "$client_pid" ] || kill "$client_pid" 2>/dev/null || :
 [ -z "$server_pid" ] || kill "$server_pid" 2>/dev/null || :
 if [ -n "${OUT:-}" ]; then mkdir -p "$OUT"; cp -r "$WORK"/. "$OUT/"; fi
 rm -r "$WORK"
}
trap cleanup EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy XDG_DOWNLOAD_DIR="$WORK"
unset DISPLAY WAYLAND_DISPLAY
mkdir -p "$WORK/server/data" "$WORK/client/data"
port=$((27500 + RANDOM % 400))
"$ENGINE" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$WORK/server" +set port "$port" +set sv_public 0 +set bot_number 2 +map stormkeep > "$WORK/server.log" 2>&1 & server_pid=$!
for n in $(seq 1 600); do grep -q 'Server spawned' "$WORK/server.log" && break; sleep .1; done
grep -q 'Server spawned' "$WORK/server.log" || { echo 'FAIL: server did not start'; exit 1; }
DELAY=${DELAY:-15}
cat > "$WORK/client/data/run.cfg" <<CFG
seta touch_profile_done 1
connect 127.0.0.1:$port
defer $((12 + DELAY)) "touch_join_answered; togglemenu 0"
defer $((16 + DELAY)) "+attack"
defer $((17 + DELAY)) "-attack"
defer $((19 + DELAY)) "_touch_test_spectate_inputs"
defer $((20 + DELAY)) "_touch_test_spectate_inputs"
defer $((21 + DELAY)) "_touch_test_spectate_inputs"
defer $((22 + DELAY)) "_touch_test_spectate_inputs"
defer $((23 + DELAY)) "_touch_test_spectate_inputs"
defer $((24 + DELAY)) "_touch_test_spectate_inputs"
defer $((25 + DELAY)) "_touch_test_spectate_inputs"
defer $((26 + DELAY)) "_touch_test_spectate_inputs"
defer $((27 + DELAY)) "_touch_test_spectate_inputs"
defer $((28 + DELAY)) "_touch_test_spectate_inputs"
defer $((29 + DELAY)) "_touch_test_spectate_inputs"
defer $((30 + DELAY)) "_touch_test_spectate_inputs 85 0"
defer $((32 + DELAY)) "screenshot spectate-touch.png"
defer $((34 + DELAY)) "_touch_test_spectate_inputs 170 1"
defer $((36 + DELAY)) "screenshot spectate-keyboard.png"
defer $((38 + DELAY)) "touch_spectate_inputs 0; _touch_test_spectate_inputs"
defer $((40 + DELAY)) "_touch_test_spectate_inputs; screenshot spectate-disabled.png"
defer $((42 + DELAY)) "quit"
CFG
size=${SIZE:-640x360}
timeout $((80 + DELAY)) "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$WORK/client" +vid_fullscreen 0 +vid_width "${size%x*}" +vid_height "${size#*x}" +set _touch_assets_ready 1 +exec run.cfg > "$WORK/client.log" 2>&1 & client_pid=$!
wait "$client_pid" || { client_pid=; echo 'FAIL: client failed'; exit 1; }; client_pid=
python3 - "$WORK/client.log" "$WORK/client" <<'PYTEST'
import pathlib
import re
import sys

log = pathlib.Path(sys.argv[1]).read_text(errors="replace")
rows = [tuple(map(int, m)) for m in re.findall(
    r"SPECTATE_INPUTS status=(-?\d+) live=(-?\d+) injected=(-?\d+) keyboard=(-?\d+) draws=(\d+)", log)]
live = [r for r in rows if r[2] == -1]
assert len(live) >= 11 and all(r[0] > 0 and r[1] >= 0 for r in live), "not following during live samples"
assert len({r[1] for r in live}) > 1, "live input mask did not change"
assert any(r[0] > 0 and r[2:4] == (85, 0) for r in rows), "touch injection missing while following"
assert any(r[0] > 0 and r[2:4] == (170, 1) for r in rows), "keyboard injection missing while following"
disabled = [r for r in rows if r[1] == -1 and r[2:4] == (170, 1)]
assert len(disabled) == 2 and disabled[0][4] == disabled[1][4], "overlay drew while disabled"
configs = list(pathlib.Path(sys.argv[2]).rglob("config.cfg"))
assert configs, "saved config.cfg missing"
config = configs[0].read_text(errors="replace")
values = re.findall(r'^(?:seta?\s+)?"?hud_panel_pressedkeys"?\s+"?([^"\s]+)', config, re.M)
assert not values or all(value == "1" for value in values), "pressedkeys default changed"
PYTEST
[ $? -eq 0 ] || { echo 'FAIL: spectator assertions'; exit 1; }
for mode in touch keyboard disabled; do
 find "$WORK/client" -iname "spectate-$mode.*" | grep -q . || { echo "FAIL: missing $mode screenshot"; exit 1; }
done
echo 'ok: following input changes, both modes captured, disabled overlay and config verified'
