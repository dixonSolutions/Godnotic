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
cat > "$WORK/client/data/run.cfg" <<CFG
seta touch_profile_done 1
connect 127.0.0.1:$port
defer 12 "touch_join_answered; togglemenu 0; _touch_test_spectate_inputs 85 0"
defer 15 "screenshot spectate-touch.png"
defer 18 "_touch_test_spectate_inputs 170 1"
defer 21 "screenshot spectate-keyboard.png"
defer 25 quit
CFG
size=${SIZE:-640x360}
timeout 60 "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$WORK/client" +vid_fullscreen 0 +vid_width "${size%x*}" +vid_height "${size#*x}" +set _touch_assets_ready 1 +exec run.cfg > "$WORK/client.log" 2>&1 & client_pid=$!
wait "$client_pid" || { client_pid=; echo 'FAIL: client failed'; exit 1; }; client_pid=
grep -q 'SPECTATE_INPUTS mask=85 keyboard=0' "$WORK/client.log" && grep -q 'SPECTATE_INPUTS mask=170 keyboard=1' "$WORK/client.log" || { echo 'FAIL: test commands did not run'; exit 1; }
for mode in touch keyboard; do find "$WORK/client" -iname "spectate-$mode.*" | grep -q . || { echo "FAIL: missing $mode screenshot"; exit 1; }; done
echo 'ok: both input modes captured'
