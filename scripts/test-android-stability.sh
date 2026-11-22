#!/bin/bash
# Regression tests for the Android crash and exit signatures of 2026-11-18:
# the join preview inside a newmap (0x233 SIGSEGV), how the engine leaves the
# process and which signals it ignores (andysu-updater SIGABRT, RenderThread and
# hwuiTask1 SIGSEGVs, silent EXIT_SELF), and SDL's hidapi receivers on Android 14+.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d "${TMPDIR:-/tmp}/android-stability.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

bash scripts/test-touch-joinpreview.sh
# skipped (exit 77) without BASEDIR game data and an engine build
if [ -n "${BASEDIR:-}" ]; then bash scripts/test-touch-warm-spawn.sh || [ $? = 77 ]; fi

cc -O1 -g -Wall -Wextra -Werror -pthread -Iengine/darkplaces \
	tests/android-stability/exit_test.c -o "$tmp/exit_test"
"$tmp/exit_test"

# The SDL glue patch, on a copy of the two lines it rewrites (the real file comes
# from the SDL tarball at build time) and on a file that no longer has them.
mkdir -p "$tmp/sdl"
cp tests/android-stability/HIDDeviceManager.fixture.java "$tmp/sdl/HIDDeviceManager.java"
cp tests/android-stability/SDLActivity.fixture.java "$tmp/sdl/SDLActivity.java"
bash scripts/android-patch-sdl-java.sh "$tmp/sdl"
bash scripts/android-patch-sdl-java.sh "$tmp/sdl" # idempotent
test "$(grep -c 'Context.RECEIVER_EXPORTED' "$tmp/sdl/HIDDeviceManager.java")" = 2 \
	|| { echo "FAIL sdl glue: expected both receivers exported" >&2; exit 1; }
if grep -nE "(^|[^A-Za-z])registerReceiver\\(" "$tmp/sdl/HIDDeviceManager.java" | grep -v RECEIVER_EXPORTED | grep -q .; then
	echo "FAIL sdl glue: a receiver without a flag is left" >&2; exit 1
fi
printf 'class HIDDeviceManager {}\n' > "$tmp/sdl/HIDDeviceManager.java"
if bash scripts/android-patch-sdl-java.sh "$tmp/sdl" 2>/dev/null; then
	echo "FAIL sdl glue: a changed SDL file must stop the build, not pass silently" >&2; exit 1
fi
# Rotation: SDL must never lock the activity to the window's aspect.
test "$(grep -c 'setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)' "$tmp/sdl/SDLActivity.java")" = 1 \
	|| { echo "FAIL sdl glue: setOrientationBis must request FULL_SENSOR" >&2; exit 1; }
if grep -n 'setRequestedOrientation(req)' "$tmp/sdl/SDLActivity.java" | grep -q .; then
	echo "FAIL sdl glue: SDL still requests its own orientation" >&2; exit 1
fi
# A keyboard attached: SDL's text field asks PhysicalKeyboard for its input type, once,
# after SDL's own and before the IME flags the chat check needs.
test "$(grep -c 'PhysicalKeyboard.inputType(outAttrs.inputType); // Godnotic: physical keys to the game' "$tmp/sdl/SDLActivity.java")" = 1 \
	|| { echo "FAIL sdl glue: SDL's text field must take its input type from PhysicalKeyboard" >&2; exit 1; }
python3 scripts/check-sdl-chat-ime.py "$tmp/sdl/SDLActivity.java" >/dev/null \
	|| { echo "FAIL sdl glue: the keyboard patch broke the chat IME flags" >&2; exit 1; }
# Typing order: the hidden text field keeps its cursor at the end and loses what a Backspace key removes.
test "$(grep -c "input method cursor stays at the end" "$tmp/sdl/SDLActivity.java")" = 3 \
	|| { echo "FAIL sdl glue: the input connection must pin its cursor (3 places) and mirror Backspace" >&2; exit 1; }
python3 scripts/check-sdl-chat-ime.py "$tmp/sdl/SDLActivity.java" --ordering >/dev/null \
	|| { echo "FAIL sdl glue: SDLInputConnection ordering guards missing" >&2; exit 1; }
echo "sdl glue (hidapi receivers, full-sensor orientation, physical keyboard): all checks passed"
