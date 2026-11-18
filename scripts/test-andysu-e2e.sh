#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# End-to-end check of a transparent AndySU update on an Android emulator/device.
#
# It installs the published 1.2.273 live-x64 host with the current AndySU host
# template patched into it, keeps the app's downloaded data, lets the public
# live-x64 channel stage the target release (1.2.276), and fails if the Phoenix relaunch crashes or
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
#   ANDYSU_E2E_TARGET       expected release (default 1.2.276)
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
TARGET="${ANDYSU_E2E_TARGET:-1.2.276}"
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
state_trial() {
    python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: print("trial"); raise SystemExit
print("trial" if d.get("trial") else "committed")
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
echo "== $TARGET is current; waiting for the trial to commit"
for i in $(seq 1 60); do
    STATE="$(adb shell "cat /data/data/$PKG/no_backup/andysu/state.json 2>/dev/null" || true)"
    [ "$(printf '%s' "$STATE" | state_trial)" = committed ] && break
    sleep 1
done
[ "$(printf '%s' "$STATE" | state_trial)" = committed ] || fail "$TARGET stayed in trial; a restart would roll it back"
[ "$(printf '%s' "$STATE" | state_id current)" = "$TARGET" ] || fail "$TARGET left current during trial; state: $STATE"
printf '%s' "$STATE" | state_bad | grep -qw "$TARGET" && fail "$TARGET was marked bad"
echo "== $TARGET committed"
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

##### Live engine swap regression #############################################
# A content-only release reaches the same request_swap()/second-engine path.
# The old engine shuts the shared libd0_blind_id down; without the Android d0
# fix its freed static bignums are initialized again and SIGSEGV in memcpy.
# Install the fixed x86_64 d0 into the current slot, start once so it is the
# loaded copy, then use the loader's offline swap hook (no host promotion) to
# exercise two engine instances in one process.
LIVE_D0="${ANDYSU_E2E_FIXED_D0:-$ROOT/tests/andysu/libd0_blind_id.x86_64.so}"
[ -f "$LIVE_D0" ] || fail "fixed d0 fixture not found: $LIVE_D0"
ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
[ "$ABI" = x86_64 ] || fail "live-swap regression fixture is x86_64, device is $ABI"
SLOT="/data/user/0/$PKG/no_backup/andysu/slots/$TARGET/lib/x86_64"
EXT="/storage/emulated/0/Android/data/$PKG/files"
adb root >/dev/null 2>&1 || true
OWNER="$(adb shell stat -c %u:%g "$SLOT/libd0_blind_id.so" | tr -d '\r')"
[ -n "$OWNER" ] || fail "current slot has no libd0_blind_id.so: $SLOT"
LIVE_OLD_D0="$WORK/libd0_blind_id.before-live-test.so"
adb shell "cp $SLOT/libd0_blind_id.so /data/local/tmp/libd0.before-live-test.so"
adb pull /data/local/tmp/libd0.before-live-test.so "$LIVE_OLD_D0" >/dev/null
adb push "$LIVE_D0" /data/local/tmp/libd0.fixed.so >/dev/null
adb shell "cp /data/local/tmp/libd0.fixed.so $SLOT/libd0_blind_id.so; chown $OWNER $SLOT/libd0_blind_id.so; chmod 400 $SLOT/libd0_blind_id.so"
adb shell "mkdir -p $EXT; touch $EXT/andysu-swap-hook; rm -f $EXT/andysu-swap-to"
adb shell am force-stop "$PKG" >/dev/null
adb logcat -c
adb shell am start -n "$PKG/.GodnoticBootActivity" >/dev/null
LIVE_PID=""
for i in $(seq 1 40); do
    LIVE_PID="$(adb shell pidof "$PKG" | tr -d '\r')"
    [ -n "$LIVE_PID" ] && break
    sleep 0.25
done
[ -n "$LIVE_PID" ] || fail "live-swap app did not start"
sleep 3
adb shell "printf '%s\n' '$SLOT/libmain.so' > $EXT/andysu-swap-to"
for i in $(seq 1 40); do
    adb logcat -d | grep -q 'engine_entry.*"swapped":1' && break
    sleep 1
done
sleep 3
adb logcat -d > "$WORK/live-swap.txt"
LIVE_PID_AFTER="$(adb shell pidof "$PKG" | tr -d '\r')"
grep -q 'engine_swap_begin' "$WORK/live-swap.txt" || fail "live swap never began (log: $WORK/live-swap.txt)"
grep -q 'engine_entry.*"swapped":1' "$WORK/live-swap.txt" || fail "second engine did not start (log: $WORK/live-swap.txt)"
grep -q 'engine_state_restored' "$WORK/live-swap.txt" || fail "second engine did not restore state (log: $WORK/live-swap.txt)"
grep -q 'engine_frames' "$WORK/live-swap.txt" || fail "second engine did not reach the frame loop (log: $WORK/live-swap.txt)"
if grep -qE 'native_crash|Fatal signal|SIGSEGV|rolled_back' "$WORK/live-swap.txt"; then
    fail "live swap crashed or rolled back (log: $WORK/live-swap.txt)"
fi
[ "$LIVE_PID" = "$LIVE_PID_AFTER" ] || fail "live swap restarted the process ($LIVE_PID -> $LIVE_PID_AFTER)"
adb shell "rm -f $EXT/andysu-swap-to $EXT/andysu-swap-hook"
echo "== live engine swap stayed in pid $LIVE_PID; no crash, rollback or restart"
echo "== live log: $WORK/live-swap.txt"
