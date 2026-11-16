#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
bin=$(mktemp "${TMPDIR:-/tmp}/andysu-healthy.XXXXXX")
trap 'rm -f "$bin"' EXIT HUP INT TERM
${CC:-cc} -std=c99 -Wall -Wextra -Werror -I"$root/android/app/src/main/cpp/andysu" \
    "$root/tests/andysu/healthy_gate_test.c" -o "$bin"
"$bin"
