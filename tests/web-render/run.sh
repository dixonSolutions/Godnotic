#!/bin/bash
# Web rendering suite: our UI on web, at tablet and phone sizes, with the real
# engine in headless Chromium. One command:
#
#   tests/web-render/run.sh [PAGE_DIR]
#
# PAGE_DIR defaults to build/web (scripts/build-web.sh + scripts/stage-web-data.sh).
# Screenshots and a JSON summary go to build/web-render/. CHROME overrides the
# browser; PORT overrides the server port.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
page="${1:-$root/build/web}"
out="${OUT:-$root/build/web-render}"
log="$out/run.log"

if [ -n "${WAYLAND_DISPLAY:-}" ] || [ "${DISPLAY:-}" = ":0" ] || [ "${DISPLAY:-}" = ":1" ]; then
    echo "run.sh: a desktop session is in the environment; run it as" >&2
    echo "  env -u WAYLAND_DISPLAY -u DISPLAY -u XAUTHORITY tests/web-render/run.sh" >&2
    exit 2
fi
if [ ! -f "$page/index.html" ]; then
    echo "run.sh: no page in $page: run scripts/build-web.sh first" >&2
    exit 2
fi
if [ ! -f "$page/data/profiles.json" ]; then
    echo "run.sh: no game data in $page/data: run scripts/stage-web-data.sh first (docs/WEB.md)" >&2
    exit 2
fi

mkdir -p "$out"
profile="$out/profile"
rm -rf "$profile"
mkdir -p "$profile"

port="${PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')}"
python3 "$root/web/serve.py" "$page" --port "$port" >"$out/serve.log" 2>&1 &
serve=$!
chrome_pid=
cleanup() { [ -n "$chrome_pid" ] && kill -9 "$chrome_pid" 2>/dev/null || true; kill "$serve" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT
for _ in $(seq 100); do curl -sf "http://127.0.0.1:$port/" >/dev/null && break; sleep 0.1; done
curl -sf "http://127.0.0.1:$port/" >/dev/null || { echo "run.sh: the page server did not start" >&2; exit 1; }

CHROME="${CHROME:-$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux64/chrome 2>/dev/null | sort -V | tail -1)}"
[ -x "$CHROME" ] || { echo "run.sh: no Chrome (set CHROME=)" >&2; exit 1; }
dbg="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
"$CHROME" --headless=new --use-angle=gl-egl --enable-gpu --ignore-gpu-blocklist \
    --disable-breakpad --disable-crash-reporter --no-first-run --no-default-browser-check \
    --autoplay-policy=no-user-gesture-required --window-size=1920,1200 \
    --user-data-dir="$profile" --remote-debugging-port="$dbg" about:blank \
    >"$out/chrome.log" 2>&1 &
chrome_pid=$!
for _ in $(seq 150); do curl -sf "http://127.0.0.1:$dbg/json/version" >/dev/null && break; sleep 0.1; done

url="http://127.0.0.1:$port/"
# Tablets the owner uses (landscape), and a phone.
sizes=("1920 1200 tablet-1920x1200" "1280 800 tablet-1280x800" "844 390 phone-844x390")
failed=0
: >"$log"
for spec in "${sizes[@]}"; do
    set -- $spec
    echo "== $3 (${1}x${2}, touch)"
    if node "$here/render.mjs" --port "$dbg" --url "$url" --out "$out" \
        --width "$1" --height "$2" --touch --label "$3" 2>&1 | tee -a "$log"; then
        echo "-- $3: PASS"
    else
        echo "-- $3: FAIL"
        failed=1
    fi
done

echo
if [ "$failed" = "0" ]; then
    echo "web-render: all sizes passed (screenshots in $out)"
else
    echo "web-render: FAILED (see $log and $out/*.png)"
fi
exit "$failed"
