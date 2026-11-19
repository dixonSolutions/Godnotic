#!/usr/bin/env bash
# Verify the bundled stock program and that the engine can resolve it from a pack.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR to game data}"
engine=${ENGINE:-$root/engine/darkplaces/darkplaces-sdl}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 - "$root/touch/godnotic-stock-csqc.pk3" <<'PY'
import binascii, hashlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    name = 'dlcache/csprogs.dat.4021257.18349'
    assert z.namelist() == [name]
    data = z.read(name)
    assert len(data) == 4021257 and binascii.crc_hqx(data, 65535) == 18349
    assert hashlib.sha256(data).hexdigest() == '7d7807166d38521aadb8109830b69580596be4c05924d1b1fd8b71c31d4def13'
PY
mkdir -p "$tmp/base/data" "$tmp/user/data"
for f in "$BASEDIR"/data/*; do ln -s "$(readlink -f "$f")" "$tmp/base/data/${f##*/}"; done
ln -s "$root/touch/godnotic-stock-csqc.pk3" "$tmp/base/data/godnotic-stock-csqc.pk3"
printf 'which dlcache/csprogs.dat.4021257.18349\ndefer 1 quit\n' > "$tmp/user/data/check.cfg"
SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy timeout 90 "$engine" -xonotic -basedir "$tmp/base" -userdir "$tmp/user" +set _touch_assets_ready 1 +exec check.cfg > "$tmp/log" 2>&1
grep -aq 'dlcache/csprogs.dat.4021257.18349 is in package godnotic-stock-csqc.pk3' "$tmp/log"
echo 'stock CSQC pack integrity and engine lookup: PASS'
