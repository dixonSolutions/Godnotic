#!/bin/bash
# Focused text entry survives a Settings navigation and is blurred before its
# dialog is removed. Run on a Wayland session; the loader uses a fixture.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bin="${GODNOTIC_LOADER_BIN:-$root/loader/target/debug/godnotic-loader}"
if [ -z "${WAYLAND_DISPLAY:-}" ] || [ ! -x "$bin" ]; then
    echo "SKIP: requires Wayland and a built GTK loader"
    exit 77
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
printf 'wait 400\nfocus welcome/form/name\ntype welcome/form/name Regression\nwait 5000\nquit\n' > "$work/actions"
GODNOTIC_TIMING=1 GDK_BACKEND=wayland GSK_RENDERER=cairo timeout 20 "$bin" \
    --fixture="$root/tests/uibridge/fixtures/focus-settings.json" --script="$work/actions" > "$work/log" 2>&1
cat "$work/log"
grep -q 'script: focused welcome/form/name' "$work/log"
grep -q 'clearing focus before removing a view' "$work/log"
if grep -Eq 'CRITICAL|panicked|segfault|could not focus' "$work/log"; then exit 1; fi
echo 'focused Settings transition: PASS'
