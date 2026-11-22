#!/bin/bash
# Runs a UI script (see src/script.rs) against the fixtures on a private Xvfb.
#   scripts/drive.sh <script> <outdir> [WxH] [dark|light]
# With LIVE=1 no fixture is shown: the app starts its UI backend (GODNOTIC_START; the tests
# use a stand-in engine) as it does for a player. DRIVE_ENV="A=1 B=2" passes variables in.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$1"; OUT="$2"; SIZE="${3:-1280x800}"; SCHEME="${4:-dark}"
mkdir -p "$OUT"
TMP="$OUT/.script"
sed "s#OUT/#$OUT/#g" "$SCRIPT" > "$TMP"
BIN="${GODNOTIC_LOADER_BIN:-$HERE/target/release/godnotic-loader}"
# map previews: GODNOTIC_DATA_DIRS, else the app's own data folders and an installed Xonotic (data-dirs.sh)
. "$HERE/scripts/data-dirs.sh"
ENVS=()
for kv in ${DRIVE_ENV:-}; do ENVS+=("--env=$kv"); done
if [ "${LIVE:-}" = 1 ]; then
    ENVS+=(--env=GODNOTIC_TEST_NATIVE_BACKEND=1 --env=GODNOTIC_SKIP_DATA_CHECK=1)
    FX=()
else
    FX=(--fixture="${FIXTURE:-home}" --fixtures-dir="$HERE/../tests/uibridge/fixtures")
fi
exec "$HERE/scripts/run-headless.sh" --size 2000x1300 -- flatpak run --filesystem=home \
    "${GN_DATA_FS[@]}" "${ENVS[@]}" --env=GODNOTIC_DATA_DIRS="$GN_DATA_DIRS" --command="$BIN" org.gnome.Platform//${XT_GNOME_VERSION:-48} \
    "${FX[@]}" --size="$SIZE" "--$SCHEME" --script="$TMP"
