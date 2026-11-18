#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# End-to-end check of a transparent AndySU update on an Android emulator/device.
#
# It installs the published 1.2.273 live-x64 host with the current AndySU host
# template patched into it, keeps the app's downloaded data, lets the public
# live-x64 channel stage 1.2.275, and fails if the Phoenix relaunch crashes or
# the update rolls back. The game data download is not required: the host update
# is independent of the boot screen.
#
#   scripts/test-andysu-e2e.sh
#
# Override the defaults with:
#   ANDYSU_E2E_BASE_APK     published host APK to split (default /tmp/andysu-e2e-assets/Godnotic-live-1.2.273-x64.apk)
#   ANDYSU_HOST_TEMPLATE    andysu-host-template.zip (default the cx-andysu build output)
#   ANDYSU_E2E_WORK         work directory for the patched host and logs
#   ANDROID_SDK_ROOT / ADB / ANDYSU_E2E_SERIAL
#   ANDYSU_E2E_TARGET       expected release (default 1.2.275)
#
# The host template must be built from the tree that contains the Phoenix fix:
#   cd android && ./gradlew :host-template:andysuHostTemplate -Pandysu.abis=x86_64
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"
BT="${BUILD_TOOLS:-$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)}"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/java/jdk-21}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
BASE_APK="${ANDYSU_E2E_BASE_APK:-/tmp/andysu-e2e-assets/Godnotic-live-1.2.273-x64.apk}"
TEMPLATE="${ANDYSU_HOST_TEMPLATE:-$HOME/.cache/godnotic-work/cx-andysu/android/host-template/build/andysu/andysu-host-template.zip}"
WORK="${ANDYSU_E2E_WORK:-/tmp/andysu-e2e-run}"
PKG=io.github.dixonsolutions.godnotic
TARGET="${ANDYSU_E2E_TARGET:-1.2.275}"
SERIAL="${ANDYSU_E2E_SERIAL:-}"

adb() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
fail() { echo "E2E FAIL: $*" >&2; exit 1; }

[ -f "$BASE_APK" ] || fail "base APK not found: $BASE_APK"
[ -f "$TEMPLATE" ] || fail "host template not found: $TEMPLATE (build :host-template:andysuHostTemplate)"
[ -x "$BT/apksigner" ] || fail "apksigner not found under $BT"
[ -x "$BT/zipalign" ] || fail "zipalign not found under $BT"
[ -x "$ADB" ] || fail "adb not found: $ADB"
adb get-state >/dev/null 2>&1 || fail "no device; start the emulator first"
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || fail "device is not booted"

mkdir -p "$WORK"
HOST="$WORK/andysu-e2e-host.apk"
KEYSTORE="$WORK/test.jks"
mkdir -p "$WORK/apk"
if [ ! -f "$HOST" ] || [ "$BASE_APK" -nt "$HOST" ] || [ "$TEMPLATE" -nt "$HOST" ]; then
    echo "== building patched host from $BASE_APK"
    rm -rf "$WORK/apk"; mkdir -p "$WORK/apk"
    (cd "$WORK/apk" && unzip -q "$BASE_APK")
    (cd "$WORK/apk" && unzip -p "$TEMPLATE" classes.dex > classes.dex)
    for so in libandysu_core.so libandysu_host.so; do
        (cd "$WORK/apk" && unzip -p "$TEMPLATE" "lib/x86_64/$so" > "lib/x86_64/$so")
    done
    rm -rf "$WORK/apk/META-INF"
    (cd "$WORK/apk" && zip -q -0 -r "$WORK/unsigned.apk" .)
    "$BT/zipalign" -p -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair -keystore "$KEYSTORE" -alias test -keyalg RSA -keysize 2048 \
            -validity 10000 -storepass testpass -keypass testpass -dname 'CN=AndySU E2E Test' >/dev/null 2>&1
    fi
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:testpass --key-pass pass:testpass \
        --out "$HOST" "$WORK/aligned.apk"
else
    echo "== reusing $HOST"
fi

echo "== installing $HOST"
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
if ! adb install -r -d "$HOST" >/dev/null 2>&1; then
    echo "== signature differs; uninstalling (app data will be lost)"
    adb uninstall "$PKG" >/dev/null 2>&1 || true
    adb install "$HOST" >/dev/null
fi

# Leave the downloaded game data alone; reset only the frozen host's state so
# this test can be rerun against the same app install.
adb root >/dev/null 2>&1 || true
adb shell "rm -rf /data/data/$PKG/no_backup/andysu; mkdir -p /data/data/$PKG/no_backup/andysu"
APPUID="$(adb shell stat -c %u "/data/data/$PKG" | tr -d '\r')"
adb shell "chown -R $APPUID:$APPUID /data/data/$PKG/no_backup/andysu"

state_id() { # $1 = current or pending
    python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: print(""); raise SystemExit
x=d.get(sys.argv[1]) or {}
print(x.get("id") or "")
' "$1"
}
state_bad() {
    python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: print(""); raise SystemExit
print(" ".join(str(x) for x in (d.get("bad") or [])))
'
}
wait_for() { # $1 = current|pending, $2 = seconds
    local i
    for i in $(seq 1 "$(($2 / 2))"); do
        STATE="$(adb shell "cat /data/data/$PKG/no_backup/andysu/state.json 2>/dev/null" || true)"
        [ "$(printf '%s' "$STATE" | state_id "$1")" = "$TARGET" ] && return 0
        sleep 2
    done
    return 1
}

adb logcat -c
adb shell am start -n "$PKG/.GodnoticBootActivity" >/dev/null
echo "== waiting for $TARGET"
if ! wait_for current 120; then
    STATE="$(adb shell "cat /data/data/$PKG/no_backup/andysu/state.json 2>/dev/null" || true)"
    echo "== staged but not promoted; requesting the next start"
    [ "$(printf '%s' "$STATE" | state_id pending)" = "$TARGET" ] || fail "$TARGET was neither current nor pending; state: $STATE"
    adb shell am force-stop "$PKG" >/dev/null
    adb shell am start -n "$PKG/.GodnoticBootActivity" >/dev/null
    wait_for current 120 || fail "$TARGET did not become current after the next start"
fi
STATE="$(adb shell "cat /data/data/$PKG/no_backup/andysu/state.json 2>/dev/null" || true)"
[ "$(printf '%s' "$STATE" | state_id current)" = "$TARGET" ] || fail "$TARGET is not current; state: $STATE"
printf '%s' "$STATE" | state_bad | grep -qw "$TARGET" && fail "$TARGET is marked bad"
echo "== $TARGET is current"
adb logcat -d > "$WORK/logcat.txt"
if grep -qE 'FATAL EXCEPTION|Process: .*:andysu_phoenix.*ClassNotFoundException|Unable to instantiate application' "$WORK/logcat.txt"; then
    grep -nE 'FATAL EXCEPTION|ClassNotFoundException|Unable to instantiate application|Process: .*andysu_phoenix' "$WORK/logcat.txt" | tail -20 >&2
    fail "Phoenix/Application crash in logcat (saved to $WORK/logcat.txt)"
fi
EXIT="$(adb shell dumpsys activity exit-info "$PKG" 2>/dev/null || true)"
if printf '%s' "$EXIT" | grep -B8 'process=.*:andysu_phoenix' | grep -q 'reason=4'; then
    fail "Phoenix process crashed (ApplicationExitInfo); saved state: $STATE"
fi
echo "== Phoenix stayed up; no rollback"
printf '%s\n' "$STATE" | sed -n '1,30p'
echo "== log: $WORK/logcat.txt"
