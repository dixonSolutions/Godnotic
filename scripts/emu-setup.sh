#!/usr/bin/env bash
# Boot the headless emulator (read-only AVD: every boot is a fresh install), cut it off from the internet except the host,
# put the game data and the slim bundle in the app's data dir (no download), install the debug APK, start the engine
# activity directly (BootActivity would try to download) and enter a name. Under flock ~/.cache/godnotic-work/emulator.lock.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
A=~/Android/Sdk/platform-tools/adb; S=emulator-5584; PKG=io.github.dixonsolutions.godnotic.mplist
G=~/.var/app/io.github.dixonSolutions.Godnotic/data/godnotic/data; D=/sdcard/Android/data/$PKG/files/data
~/.cache/godnotic-work/emu-shot.sh start | tail -1
$A -s $S root > /dev/null 2>&1; sleep 3
$A -s $S shell 'iptables -F OUTPUT; iptables -A OUTPUT -o lo -j ACCEPT; iptables -A OUTPUT -d 10.0.2.2 -j ACCEPT; iptables -A OUTPUT -d 10.0.2.15 -j ACCEPT; iptables -A OUTPUT -j REJECT'
$A -s $S install -r -t "$root/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1
# the app creates its own data directories on first start (a directory made by adb as root is not readable by it)
$A -s $S shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 12; $A -s $S shell am force-stop $PKG
$A -s $S shell settings put system user_rotation 0
for f in xonotic-20261107-data.pk3 xonotic-20261107-maps.pk3 font-unifont-20261107.pk3 font-xolonium-20261107.pk3; do $A -s $S push $G/$f $D/ | tail -1; done
$A -s $S push "$root/build/android-cache/slim/data/." $D/ | tail -1
$A -s $S shell am start -n $PKG/io.github.dixonsolutions.godnotic.XonoticActivity | tail -1
sleep 45
$A -s $S shell input tap 1321 285; sleep 1 # "Viewing full screen" hint
$A -s $S shell input tap 960 454; sleep 1; $A -s $S shell input text EmuTester; sleep 1
$A -s $S shell input tap 1350 558; sleep 4 # Continue
~/.cache/godnotic-work/emu-shot.sh shot "$root/build/shots/emu/setup-done.png"
