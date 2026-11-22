#!/bin/bash
# Patches SDL's Android Java glue (copied out of the SDL tarball by android-build.sh).
#
#   scripts/android-patch-sdl-java.sh <dir with org/libsdl/app/*.java>
#
# HIDDeviceManager registers its USB and Bluetooth receivers without
# RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED. From Android 14 (targetSdk 34+)
# that throws, hid_init fails ("One of RECEIVER_EXPORTED or
# RECEIVER_NOT_EXPORTED should be specified ...") and USB/Bluetooth controllers
# go undetected. Both receivers hear system broadcasts and the USB permission
# result, so they are exported, as SDL 3 does. Idempotent; fails when the line
# it patches is gone (a new SDL release changed the file: look again).
set -euo pipefail
dir="${1:?usage: $0 <org/libsdl/app dir>}"
f="$dir/HIDDeviceManager.java"
test -f "$f" || { echo "android-patch-sdl-java: no $f" >&2; exit 1; }
for recv in mUsbBroadcast mBluetoothBroadcast; do
    plain="mContext.registerReceiver($recv, filter);"
    fixed="if (Build.VERSION.SDK_INT >= 33) mContext.registerReceiver($recv, filter, Context.RECEIVER_EXPORTED); else mContext.registerReceiver($recv, filter); // Godnotic: Android 14+ needs the flag"
    if grep -qF "$fixed" "$f"; then
        continue
    fi
    grep -qF "$plain" "$f" || { echo "android-patch-sdl-java: '$plain' not found in $f" >&2; exit 1; }
    python3 - "$f" "$plain" "$fixed" <<'PY'
import sys
p, a, b = sys.argv[1:]
s = open(p).read()
open(p, "w").write(s.replace(a, b))
PY
done
# Every registerReceiver call in the glue now names a flag (or is the pre-33 branch).
if grep -nE "(^|[^A-Za-z])registerReceiver\\(" "$dir"/*.java | grep -v "RECEIVER_EXPORTED\|RECEIVER_NOT_EXPORTED" | grep -q .; then
    echo "android-patch-sdl-java: a registerReceiver call without a flag is left:" >&2
    grep -nE "(^|[^A-Za-z])registerReceiver\\(" "$dir"/*.java | grep -v "RECEIVER_EXPORTED\|RECEIVER_NOT_EXPORTED" >&2
    exit 1
fi

# SDLActivity.setOrientationBis picks SENSOR_PORTRAIT or SENSOR_LANDSCAPE from the
# size of a fixed window (hint with all four orientations + w<h = portrait only),
# and runs again on every window (re)creation. Godnotic follows the device in all
# four directions at any time, so the request is always FULL_SENSOR.
a="$dir/SDLActivity.java"
test -f "$a" || { echo "android-patch-sdl-java: no $a" >&2; exit 1; }
if ! grep -qF "Godnotic: always follow the device" "$a"; then
    plain="mSingleton.setRequestedOrientation(req);"
    fixed="mSingleton.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR); // Godnotic: always follow the device"
    grep -qF "$plain" "$a" || { echo "android-patch-sdl-java: '$plain' not found in $a" >&2; exit 1; }
    python3 - "$a" "$plain" "$fixed" <<'PY'
import sys
p, a, b = sys.argv[1:]
s = open(p).read()
open(p, "w").write(s.replace(a, b))
PY
fi

# A keyboard attached: SDL's hidden text field asks for no text editor (TYPE_NULL),
# so an input method with physical-keyboard prediction (Samsung Keyboard) passes the
# keys on to the game instead of keeping them for its suggestion strip
# (PhysicalKeyboard.java). handleKeyEvent still commits each key's character.
if ! grep -qF "Godnotic: physical keys to the game" "$a"; then
    plain="                             InputType.TYPE_TEXT_FLAG_MULTI_LINE;"
    fixed="$plain
        outAttrs.inputType = io.github.dixonsolutions.godnotic.PhysicalKeyboard.inputType(outAttrs.inputType); // Godnotic: physical keys to the game"
    test "$(grep -cF "$plain" "$a")" = 1 || { echo "android-patch-sdl-java: '$plain' not found once in $a" >&2; exit 1; }
    python3 - "$a" "$plain" "$fixed" <<'PY'
import sys
p, a, b = sys.argv[1:]
s = open(p).read()
open(p, "w").write(s.replace(a, b))
PY
fi

# Typing came out in reverse after a backspace. SDL feeds the engine from a diff of
# its hidden text field (SDLInputConnection.updateText: backspaces for what left the
# end, then the new tail), so that field is only right while its cursor sits at the
# end and it holds exactly what the engine's line holds. An input method breaks both:
#  - it moves the cursor (setSelection, or a composing region that ends at 0 after a
#    delete), and the next characters land in front of the old ones;
#  - Backspace arriving as a key event (hardware or Samsung Keyboard) goes to the
#    engine but never out of the field, so the next diff is against stale text.
# The field's cursor is therefore pinned to the end, and a Backspace key event
# takes its last character out of the field too.
if ! grep -qF "Godnotic: input method cursor stays at the end" "$a"; then
    python3 - "$a" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

def once(s, anchor):
    if s.count(anchor) != 1:
        sys.exit("android-patch-sdl-java: '%s' not found exactly once in %s" % (anchor.strip(), p))

send = "        return super.sendKeyEvent(event);\n"
once(s, send)
s = s.replace(send, """        if (event.getKeyCode() == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
            // Godnotic: input method cursor stays at the end -- the key reaches the engine, so the field loses it too
            Editable field = getEditable();
            if (field != null && field.length() > 0) {
                field.delete(field.length() - 1, field.length());
                mCommittedText = field.toString();
            }
        }
""" + send)

commit = "        mCommittedText = text;\n"
once(s, commit)
s = s.replace(commit, "        android.text.Selection.setSelection(content, content.length()); // Godnotic: input method cursor stays at the end\n" + commit)

native = "    public static native void nativeCommitText(String text, int newCursorPosition);\n"
once(s, native)
s = s.replace(native, """    @Override
    public boolean setSelection(int start, int end) {
        // Godnotic: input method cursor stays at the end -- the engine line only grows and shrinks there
        Editable field = getEditable();
        if (field != null) {
            android.text.Selection.setSelection(field, field.length());
        }
        return true;
    }

""" + native)
open(p, "w").write(s)
PY
fi
