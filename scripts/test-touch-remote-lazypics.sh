#!/usr/bin/env bash
# Exercise remote CSQC picture precaches and the first world frame both ways.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR to a game data directory}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for mode in eager lazy; do
    value=0; [ "$mode" = lazy ] && value=1
    "$root/scripts/bench-load.sh" -n 1 -o "$work/$mode" -e "+set touch_load_lazypics $value" join:stormkeep > "$work/$mode.out"
    log="$work/$mode/join-stormkeep-0-1.log"
    grep -aq 'LOADTIME world_preview' "$log"
    grep -aq 'LOADTIME world_presented' "$log"
    ! grep -aq 'Host_Error\|SV_Error' "$log"
done
count() { awk '/LOADTIME game_ready/{load=1} load && /LOADTIME   image read\+decode/{print $(NF-1); exit}' "$1"; }
eager=$(count "$work/eager/join-stormkeep-0-1.log")
lazy=$(count "$work/lazy/join-stormkeep-0-1.log")
[ -n "$eager" ] && [ -n "$lazy" ] && [ "$lazy" -lt "$eager" ] || { echo "FAIL: image calls eager=$eager lazy=$lazy" >&2; exit 1; }
echo "PASS: remote preview and world presented; image loads eager=$eager lazy=$lazy"
