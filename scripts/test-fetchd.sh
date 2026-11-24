#!/usr/bin/env bash
# A background asset download must not reopen a game the user closed.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/godnotic-fetchd-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/lib"
cat > "$WORK/lib/asset-fetch.sh" <<'STUB'
xonotic_assets_are_ready() { [ -f "$1/.assets-ready" ]; }
xonotic_resolve_missing_assets() { touch "$1/.assets-ready"; }
STUB
cat > "$WORK/bin/pgrep" <<'STUB'
#!/bin/sh
exit 1
STUB
cat > "$WORK/bin/flatpak" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$EVENTS"
STUB
cat > "$WORK/bin/notify-send" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$NOTIFICATIONS"
STUB
chmod +x "$WORK/bin/pgrep" "$WORK/bin/flatpak" "$WORK/bin/notify-send"
export PATH="$WORK/bin:$PATH" GODNOTIC_LIB_DIR="$WORK/lib" EVENTS="$WORK/events" NOTIFICATIONS="$WORK/notifications"
export GODNOTIC_USER_BASE="$WORK/default"
bash "$ROOT/scripts/godnotic-fetchd.sh"
[ ! -e "$EVENTS" ]
grep -q 'Game data finished downloading\.' "$NOTIFICATIONS"
! grep -q 'Opening Godnotic' "$NOTIFICATIONS"
export GODNOTIC_USER_BASE="$WORK/opt-in" GODNOTIC_AUTO_OPEN_ON_READY=1
bash "$ROOT/scripts/godnotic-fetchd.sh"
for _ in 1 2 3 4 5; do [ -s "$EVENTS" ] && break; sleep .1; done
grep -q 'run io.github.dixonSolutions.Godnotic' "$EVENTS"
grep -q 'Opening Godnotic' "$NOTIFICATIONS"
echo 'fetchd: no automatic reopen by default; explicit opt-in works'
