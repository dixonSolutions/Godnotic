#!/bin/bash
# A browser joins a game through the web relay, end to end (join-e2e.mjs):
# the real web build in headless Chromium, a native relay and a dedicated
# server, a fake master, and an MQTT broker for signaling.
#
#   env -u WAYLAND_DISPLAY -u DISPLAY -u XAUTHORITY tests/web-bridge/join-e2e.sh
#
# PAGE      the built page with data (default build/web: scripts/build-web.sh
#           + scripts/stage-web-data.sh)
# STOCK     a directory with the stock pk3s the page's data was staged from
#           (xonotic-*-data.pk3 and the maps pk3); the servers run them with
#           the page's own zz-*.pk3, so client and server game code match
# BROKER    signaling broker URL; default: a local tests/web-bridge/mqtt_broker.py.
#           BROKER=wss://broker.emqx.io:8084/mqtt tries the public default.
# DEDICATED darkplaces-dedicated built with the bridge (make sv-release)
# CHROME    the browser (default: Playwright's Chromium in ~/.cache)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
if [ -n "${WAYLAND_DISPLAY:-}" ] || [ "${DISPLAY:-}" = ":0" ] || [ "${DISPLAY:-}" = ":1" ]; then
    echo "join-e2e.sh: run it headless: env -u WAYLAND_DISPLAY -u DISPLAY -u XAUTHORITY $0" >&2
    exit 2
fi
page="${PAGE:-$root/build/web}"
stock="${STOCK:?set STOCK to the directory with the stock pk3s (xonotic-*-data.pk3, maps)}"
dedicated="${DEDICATED:-$root/engine/darkplaces/darkplaces-dedicated}"
out="${OUT:-$root/build/web-join}"
rm -rf "$out"
mkdir -p "$out/base/data"

# Server data: stock packs + the page's own zz-*.pk3 (same progs as the client).
for f in "$stock"/xonotic-*-data.pk3 "$stock"/xonotic-*-maps*.pk3 "$stock"/xonotic-*-xoncompat.pk3 "$stock"/font-*.pk3 "$stock"/xonotic.cfg; do
    [ -e "$f" ] && ln -s "$(readlink -f "$f")" "$out/base/data/"
done
python3 -I - "$page/data" "$out/base/data" <<'PY'
import json, os, sys
data, dst = sys.argv[1:3]
prof = json.load(open(os.path.join(data, 'profiles.json')))
man = json.load(open(os.path.join(data, prof['profiles'][prof['default']]['manifest'])))
for f in man['files']:
    if f['name'].startswith('zz-') and f['name'].endswith('.pk3'):
        with open(os.path.join(dst, f['name']), 'wb') as o:
            for c in f['chunks']:
                o.write(open(os.path.join(data, 'blobs', c + '.bin'), 'rb').read())
PY

pids=()
cleanup() { for p in "${pids[@]}"; do kill -9 "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT
if [ -z "${BROKER:-}" ]; then
    python3 -I "$here/mqtt_broker.py" --log "$out/broker.log" >"$out/broker.out" &
    pids+=($!)
    for _ in $(seq 50); do grep -q listening "$out/broker.out" 2>/dev/null && break; sleep 0.1; done
    BROKER="ws://127.0.0.1:$(awk '/listening/{print $2}' "$out/broker.out")/mqtt"
fi
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
python3 "$root/web/serve.py" "$page" --port "$port" >"$out/serve.log" 2>&1 &
pids+=($!)
for _ in $(seq 100); do curl -sf "http://127.0.0.1:$port/" >/dev/null && break; sleep 0.1; done
CHROME="${CHROME:-$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux64/chrome 2>/dev/null | sort -V | tail -1)}"
cdp="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
"$CHROME" --headless=new --use-angle=gl-egl --enable-gpu --ignore-gpu-blocklist \
    --disable-breakpad --disable-crash-reporter --no-first-run --no-default-browser-check \
    --autoplay-policy=no-user-gesture-required --window-size=1280,800 \
    --user-data-dir="$out/profile" --remote-debugging-port="$cdp" about:blank >"$out/chrome.log" 2>&1 &
pids+=($!)
for _ in $(seq 150); do curl -sf "http://127.0.0.1:$cdp/json/version" >/dev/null && break; sleep 0.1; done
node "$here/join-e2e.mjs" --cdp "$cdp" --url "http://127.0.0.1:$port/" --broker "$BROKER" \
    --dedicated "$dedicated" --basedir "$out/base" --out "$out" ${MAP:+--map "$MAP"}
