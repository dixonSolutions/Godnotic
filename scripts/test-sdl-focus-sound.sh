#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Compile the real SDL callback with a tiny fake mixer, so both audio paths
# are checked for actual samples rather than only checking window flags.
python3 - "$tmp/callback.inc" "$tmp/block.inc" <<'PY'
import pathlib, sys
s = pathlib.Path('engine/darkplaces/snd_sdl.c').read_text()
start = s.index('static void Buffer_Callback')
end = s.index('\n\n/*', start)
pathlib.Path(sys.argv[1]).write_text(s[start:end])
sound = pathlib.Path('engine/darkplaces/snd_main.c').read_text()
start = sound.index('snd_blocked = (snd_mutewhenidle.integer == 1')
end = sound.index(';', start) + 1
pathlib.Path(sys.argv[2]).write_text(sound[start:end])
video = pathlib.Path('engine/darkplaces/vid_sdl.c').read_text()
for line in ('Touch_SDLVisibility(event.window.event, &vid_hidden)',
             'vid_activewindow = !vid_hidden && vid_hasfocus;',
             'case SDL_WINDOWEVENT_FOCUS_GAINED:\n\t\t\t\t\t\tvid_hasfocus = true;',
             'case SDL_WINDOWEVENT_FOCUS_LOST:\n\t\t\t\t\t\tvid_hasfocus = false;'):
    assert line in video, line
PY
cc -std=c99 -Wall -Wextra -Wno-unused-parameter -Iengine/darkplaces -I"$tmp" $(sdl2-config --cflags) tests/sdl-focus-sound.c -o "$tmp/test"
"$tmp/test"
