#!/usr/bin/env bash
# Join an explicitly supplied stock dedicated server with a fresh client and
# the bundled CSQC cache. The server must advertise the chosen program.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR to client game data}"
: "${STOCK_BASEDIR:?set STOCK_BASEDIR to the stock server game data}"
: "${STOCK_ENGINE:?set STOCK_ENGINE to a stock dedicated engine}"
export ENGINE=${ENGINE:-$root/engine/darkplaces/darkplaces-sdl}
crc=${STOCK_CSQC_CRC:-18349}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/base/data"
for f in "$BASEDIR"/data/*; do ln -s "$(readlink -f "$f")" "$tmp/base/data/${f##*/}"; done
ln -s "$root/touch/godnotic-stock-csqc.pk3" "$tmp/base/data/godnotic-stock-csqc.pk3"
# bench-load supplies the client basedir to both engines. Substitute the
# explicit server fixture without changing any other arguments.
cat > "$tmp/server" <<'SH'
#!/usr/bin/env bash
args=()
while [ "$#" -gt 0 ]; do
    if [ "$1" = -basedir ]; then args+=(-basedir "$STOCK_BASEDIR"); shift 2
    else args+=("$1"); shift; fi
done
exec "$STOCK_ENGINE" "${args[@]}"
SH
chmod +x "$tmp/server"
export STOCK_BASEDIR STOCK_ENGINE
BASEDIR="$tmp/base" SV_ENGINE="$tmp/server" "$root/scripts/bench-load.sh" -n 1 -o "$tmp/bench" join:stormkeep > "$tmp/out" 2>&1 || { cat "$tmp/out"; exit 1; }
log="$tmp/bench/join-stormkeep-0-1.log"
grep -aq "client: program loaded (crc $crc," "$log"
grep -aq 'LOADTIME world_presented' "$log"
if grep -aq 'Downloading new CSQC\|Downloaded "dlcache/csprogs' "$log"; then
    echo 'FAIL: bundled CSQC was downloaded' >&2; exit 1
fi
echo 'stock first join uses bundled CSQC: PASS'
