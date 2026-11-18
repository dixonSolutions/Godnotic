#!/bin/bash
# Checks the join preview's pure scene rules (touch_joinpreview_logic.h).
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d "${TMPDIR:-/tmp}/joinpreview.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cc -O1 -g -Wall -Wextra -Werror -Iengine/darkplaces \
	tests/touch-joinpreview/joinpreview_test.c -o "$tmp/joinpreview_test"
"$tmp/joinpreview_test"
