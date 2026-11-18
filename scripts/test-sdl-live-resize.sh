#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cc -std=c99 -Wall -Wextra -Werror -Iengine/darkplaces $(sdl2-config --cflags) tests/sdl-live-resize.c $(sdl2-config --libs) -o "$tmp/test"
SDL_VIDEODRIVER=offscreen "$tmp/test"
