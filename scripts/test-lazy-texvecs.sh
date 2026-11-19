#!/usr/bin/env bash
# Local map start with the animation-frame tangents built at load (0) and on
# first draw (1, default): both reach the world, and the lazy load builds far
# fewer frames. Prints model-load time of each for the record.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${BASEDIR:?set BASEDIR to a game data directory}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for mode in eager lazy; do
    value=0; [ "$mode" = lazy ] && value=1
    "$root/scripts/bench-load.sh" -n 1 -o "$work/$mode" -e "+set touch_load_lazytexvecs $value" map:stormkeep > "$work/$mode.out"
    log="$work/$mode/map-stormkeep-1.log"
    grep -aq 'LOADTIME world_presented' "$log"
    ! grep -aq 'Host_Error\|SV_Error' "$log"
done
count() { awk '/LOADTIME game_ready/{load=1} load && /LOADTIME   morph texvec frames built/{print $NF; exit}' "$1"; }
modelms() { awk '/LOADTIME game_ready/{load=1} load && /LOADTIME   model load/{print $4; exit}' "$1"; }
eager=$(count "$work/eager/map-stormkeep-1.log")
lazy=$(count "$work/lazy/map-stormkeep-1.log")
[ -n "$eager" ] && [ -n "$lazy" ] && [ "$lazy" -lt "$eager" ] && [ $((lazy * 2)) -lt "$eager" ] || { echo "FAIL: texvec frames eager=$eager lazy=$lazy" >&2; exit 1; }
echo "PASS: world presented both ways; texvec frames built at load eager=$eager lazy=$lazy; model load ms eager=$(modelms "$work/eager/map-stormkeep-1.log") lazy=$(modelms "$work/lazy/map-stormkeep-1.log")"
