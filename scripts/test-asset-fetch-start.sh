#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
test_root=$tmp
source "$root/scripts/lib/asset-fetch.sh"
xonotic_music_assets_missing() { return 0; }
xonotic_progress_write() { :; }
xonotic_fetch_parallel_jobs() { echo 2; }
xonotic_autobuild_content_length() {
    if [ "$case_name" = fresh ]; then
        for _ in {1..100}; do [ -f "$test_root/started" ] && break; sleep .01; done
        [ -f "$test_root/started" ] || return 1
    else
        [ ! -f "$test_root/started" ] || return 1
    fi
    echo 1
}
xonotic_start_autobuild_download() {
    : > "$test_root/started"
    ( printf x > "$1" ) &
    XONOTIC_DOWNLOAD_PID=$!
}
xonotic_install_autobuild_zip() { [ "$(cat "$2")" = x ]; : > "$test_root/installed"; }
case_name=fresh
xonotic_fetch_autobuild_assets "$tmp/fresh"
[ -f "$test_root/installed" ]
rm "$test_root/started" "$test_root/installed"
case_name=complete
mkdir -p "$tmp/complete/.fetch-tmp"
printf x > "$tmp/complete/.fetch-tmp/godnotic-data.zip"
xonotic_fetch_autobuild_assets "$tmp/complete"
[ -f "$test_root/installed" ] && [ ! -f "$test_root/started" ]
echo 'asset fresh starts before probe; complete resume avoids GET: PASS'
