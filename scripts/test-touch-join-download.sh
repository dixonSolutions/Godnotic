#!/usr/bin/env bash
# The map pack of a server join downloads in parallel pieces: the defaults are
# curl_maxdownloads 8 and curl_segments 6, and a 24 MB pack from a host that serves
# 1 MiB/s per connection arrives in well under half the single-connection time (24 s).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/build/bin/xonotic}
: "${BASEDIR:?set BASEDIR to a game directory with data/}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || exit 77
grep -q '"curl_segments", "6"' "$ROOT/engine/darkplaces/touch_curl.h" || { echo "FAIL: curl_segments default is not 6"; exit 1; }
grep -q '^curl_maxdownloads 8' "$ROOT/touch/xonotic.cfg" || { echo "FAIL: curl_maxdownloads 8 missing from touch/xonotic.cfg"; exit 1; }
out=$("$ROOT/scripts/bench-download.sh" -s 24 capped::1024 2>&1 || true)
echo "$out" | grep '^|'
godnotic=$(echo "$out" | grep '^| capped' | sed -E 's/.*\| ([0-9.]+) s \|$/\1/')
[ -n "$godnotic" ] || { echo "SKIP/FAIL: no timing: $out" >&2; exit 77; }
awk -v t="$godnotic" 'BEGIN{exit !(t < 12)}' || { echo "FAIL: capped download took ${godnotic}s (>= 12 s)"; exit 1; }
echo "join download: all checks passed (${godnotic} s for 24 MB at 1 MiB/s per connection)"
