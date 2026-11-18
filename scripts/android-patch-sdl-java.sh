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
