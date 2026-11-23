#!/bin/bash
# Resume against a server that ignores Range (1289 q1android).
#
# A partial pack is on disk, the server (Python's http.server, as on the Quake 1
# server) answers every request with 200 and the whole file. libcurl refuses to
# append that to the partial (error 33, CURLE_RANGE_ERROR); every retry resumed from
# the same bytes and failed the same way, so the join went on without the pack.
# Now the file is emptied and fetched again from byte 0.
#
# usage: scripts/test-curl-norange.sh <engine binary> <basedir with the stock data>
# Needs Xvfb, python3 (XT_LIBS: extra library dir, e.g. a libjpeg the engine can load). Not in CI: it needs a built engine and the stock data.
set -u
ENG=${1:?engine binary}; BASE=${2:?basedir}
W=$(mktemp -d); trap 'kill $SRV $XV 2>/dev/null; rm -rf "$W"' EXIT
mkdir -p "$W/srv" "$W/ud/data/dlcache"
python3 - "$W" <<'PY'
import sys,zipfile,random
w=sys.argv[1]; random.seed(1)
with zipfile.ZipFile(w+'/srv/x.pk3','w',zipfile.ZIP_STORED) as z:
    z.writestr('maps/x.txt','hi'); z.writestr('data.bin',random.randbytes(3<<20))
d=open(w+'/srv/x.pk3','rb').read()
open(w+'/ud/data/dlcache/x.pk3','wb').write(d[:1000000]); open(w+'/ud/data/dlcache/x.pk3.meta','w').write('none')
PY
(cd "$W/srv" && exec python3 -m http.server 8097 --bind 127.0.0.1 >/dev/null 2>&1) & SRV=$!
Xvfb :89 -screen 0 800x600x24 >/dev/null 2>&1 & XV=$!
sleep 2
printf 'cl_curl_enabled 1\ndefer 1 "curl --pak --forthismap http://127.0.0.1:8097/x.pk3"\ndefer 12 quit\n' > "$W/ud/data/t.cfg"
LD_LIBRARY_PATH="${XT_LIBS:-}${XT_LIBS:+:}${LD_LIBRARY_PATH:-}" DISPLAY=:89 SDL_VIDEODRIVER=x11 SDL_AUDIODRIVER=dummy timeout 60 "$ENG" -xonotic -nosound -basedir "$BASE" -userdir "$W/ud" \
  +vid_fullscreen 0 +vid_width 800 +vid_height 600 +exec t.cfg > "$W/log" 2>&1
if cmp -s "$W/srv/x.pk3" "$W/ud/data/dlcache/x.pk3" && grep -q "starting again from byte 0" "$W/log"; then
  echo "PASS: partial pack replaced by the whole file"; exit 0
fi
echo "FAIL: pack is $(stat -c %s "$W/ud/data/dlcache/x.pk3") bytes of $(stat -c %s "$W/srv/x.pk3")"; grep -i "download\|curl error" "$W/log" | head; exit 1
