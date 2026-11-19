#!/usr/bin/env bash
# Both downloads must start without waiting for the other, including on failure.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
source "$root/scripts/lib/asset-fetch.sh"
xonotic_assets_are_ready() { return 1; }
_xonotic_asset_discover_lib() { :; }
xonotic_progress_write() { :; }
xonotic_ensure_menu_theme_track() {
    : > "$tmp/theme-started"
    for _ in {1..100}; do [ -f "$tmp/archive-started" ] && return 0; sleep .02; done
    return 1
}
xonotic_fetch_autobuild_assets() {
    : > "$tmp/archive-started"
    for _ in {1..100}; do [ -f "$tmp/theme-started" ] && return 1; sleep .02; done
    return 0
}
if xonotic_fetch_game_assets "$tmp/data" >/dev/null; then
    echo 'FAIL: incomplete assets reported ready' >&2; exit 1
fi
[ -f "$tmp/theme-started" ] && [ -f "$tmp/archive-started" ]
echo 'asset theme and archive start together: PASS'
