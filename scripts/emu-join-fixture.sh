#!/usr/bin/env bash
# A local dedicated server that makes a client download FIVE packs before it can play, like a public custom-map server
# (Hightime: 5 files): the map's own pack is third in the queue and holds the only levelshot (as levelshots/hightx.jpg for the
# map hightx_r1: the first revision's name), the others are padding. The packs are served over HTTP with Range and a per-connection
# speed limit so that every file stays on screen long enough to look at. The Android emulator reaches the host at 10.0.2.2.
#
#   BASEDIR=<basedir with the stock maps pk3> scripts/emu-join-fixture.sh start|stop [work-dir]
#   server: udp 26980   packs: http://10.0.2.2:28182/   (join 10.0.2.2:26980 from the emulator)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
act=${1:-start}; work=${2:-${EMU_FIXTURE_DIR:-/tmp/emu-join-fixture}}
: "${BASEDIR:?set BASEDIR}"
engine=${ENGINE:-$root/build/bin/xonotic}
kbps=${EMU_FIXTURE_KBPS:-450}
case "$act" in
stop) [ -f "$work/pids" ] && xargs -r kill < "$work/pids" 2> /dev/null || true; rm -f "$work/pids"; exit 0 ;;
start) ;;
*) echo "usage: $0 start|stop [dir]"; exit 2 ;;
esac
mkdir -p "$work/www" "$work/sv/data"
maps=$(ls "$BASEDIR"/data/*maps*.pk3 | head -1)
python3 -I - "$maps" "$work/www" <<'PY'
import os, sys, zipfile
src, www = sys.argv[1], sys.argv[2]
def pad(name, probe, mb):
    with zipfile.ZipFile(os.path.join(www, name), "w") as z:
        z.writestr(probe, "pad")
        z.writestr("pad/%s.bin" % name, os.urandom(mb * 1024 * 1024), compress_type=zipfile.ZIP_STORED)
pad("hz1-textures.pk3", "pad/p1.txt", 3)
pad("hz2-models.pk3", "pad/p2.txt", 3)
with zipfile.ZipFile(src) as s, zipfile.ZipFile(os.path.join(www, "hz3-cts_hightx_r1.pk3"), "w") as z:
    z.writestr("maps/hightx_r1.bsp", s.read("maps/stormkeep.bsp"), compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("levelshots/hightx.jpg", s.read("maps/stormkeep.jpg"), compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("pad/hz3.bin", os.urandom(2 * 1024 * 1024), compress_type=zipfile.ZIP_STORED)
pad("hz4-sounds.pk3", "pad/p4.txt", 3)
pad("hz5-extra.pk3", "pad/p5.txt", 3)
PY
cp "$work"/www/*.pk3 "$work/sv/data/"
python3 "$root/tests/net/range_http.py" "$work/www" 28182 "$kbps" > "$work/http.log" 2>&1 & echo $! > "$work/pids"
SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy "$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port 26980 +set sv_public 0 +set bot_number 0 +set minplayers 0 \
  +set sv_curl_defaulturl http://10.0.2.2:28182/ +set sv_curl_serverpackages "pad/p1.txt pad/p2.txt maps/hightx_r1.bsp pad/p4.txt pad/p5.txt" +set hostname "Hightime-like 5 packs" +map hightx_r1 > "$work/sv.log" 2>&1 & echo $! >> "$work/pids"
for _ in $(seq 1 100); do grep -q 'Server spawned' "$work/sv.log" && break; sleep .2; done
# a second, public-like server on a STOCK map (the picture is in the local data: it must show from the first frame)
mkdir -p "$work/sv2/data"
SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy "$engine" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv2" +set port 26981 +set sv_public 0 +set bot_number 0 +set minplayers 0 +set hostname "Stock map server" +map ${EMU_STOCK_MAP:-stormkeep} > "$work/sv2.log" 2>&1 & echo $! >> "$work/pids"
grep -q 'Server spawned' "$work/sv.log" && echo "fixture up: join 10.0.2.2:26980, packs at http://10.0.2.2:28182/ (${kbps} KB/s each)" || { tail -20 "$work/sv.log"; exit 1; }
