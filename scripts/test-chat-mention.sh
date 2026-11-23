#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The chat features' pure parts: @mention matching, the typing line, and the
# chat log (ring buffer, fade, scroll box, export). No engine, no game data.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for t in mention chatlog; do
	"${CC:-cc}" -std=gnu99 -Wall -Wextra -Werror -I"$root/engine/darkplaces" "$root/tests/mention/${t}_test.c" -o "$work/$t-test" -lm
	"$work/$t-test"
done
