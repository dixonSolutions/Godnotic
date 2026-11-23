#!/usr/bin/env bash
# Join a REAL public server on the emulator, with real adb taps, and record the times.
#   ~/.cache/godnotic-work/emu-slot.sh scripts/emu-real-join.sh <out-dir>
# Boots the emulator in this session's slot (EMU_PORT / ANDROID_SERIAL), installs the debug APK, seeds the game data, opens
# Multiplayer on the real master list, picks the first populated server on a STOCK map (scripts/mapshots-stock.txt), taps its row
# (the info popup), then Join in the popup. Frames are named f<ms since the tap>.png; times.txt has the milestones.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$root/build/shots/real}; mkdir -p "$out"; rm -f "$out"/*.png "$out"/*.xml
A="timeout 30 $HOME/Android/Sdk/platform-tools/adb"; S=${ANDROID_SERIAL:?run under emu-slot.sh}
PKG=io.github.dixonsolutions.godnotic.mplist
G=~/.var/app/io.github.dixonSolutions.Godnotic/data/godnotic/data; D=/sdcard/Android/data/$PKG/files/data
now() { date +%s.%N; }
# tap the on-screen node whose text (or class) matches: prints nothing when there is none
uitap() { # <text|class> <value>
  $A -s $S shell uiautomator dump /sdcard/u.xml > /dev/null 2>&1; $A -s $S pull -q /sdcard/u.xml /tmp/u-$S.xml 2> /dev/null
  local xy; xy=$(python3 -I - "$1" "$2" /tmp/u-$S.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
key, val, path = sys.argv[1:4]
try:
    for n in ET.parse(path).iter("node"):
        if n.get(key) == val and n.get("bounds"):
            a = list(map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
except Exception:
    pass
PY
)
  [ -n "$xy" ] && { $A -s $S shell input tap $xy; return 0; }; return 1
}
# the widest node with this text (the info popup's Join is twice as wide as the bottom bar's)
uitapw() {
  $A -s $S shell uiautomator dump /sdcard/u.xml > /dev/null 2>&1; $A -s $S pull -q /sdcard/u.xml /tmp/u-$S.xml 2> /dev/null
  local xy; xy=$(python3 -I - "$1" /tmp/u-$S.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
best = None
try:
    for n in ET.parse(sys.argv[2]).iter("node"):
        if n.get("text") == sys.argv[1] and n.get("bounds"):
            a = list(map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups()))
            if best is None or a[2] - a[0] > best[0]: best = (a[2] - a[0], (a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
except Exception:
    pass
if best: print(best[1], best[2])
PY
)
  [ -n "$xy" ] && { $A -s $S shell input tap $xy; return 0; }; return 1
}
# a loaded host makes Android show "System UI isn't responding": press Wait (only when it is there)
unanr() { for k in 1 2 3; do $A -s $S shell uiautomator dump /sdcard/anr.xml > /dev/null 2>&1; $A -s $S pull -q /sdcard/anr.xml /tmp/anr-$S.xml 2> /dev/null; grep -q "isn't responding" /tmp/anr-$S.xml 2> /dev/null && $A -s $S shell input tap 696 684 || break; sleep 2; done; }
ms() { python3 -c "print(int(($2-$1)*1000))" ; }
log() { echo "$*" | tee -a "$out/times.txt"; }
: > "$out/times.txt"
~/.cache/godnotic-work/emu-shot.sh start || { echo 'emulator did not start'; exit 1; }
$A -s $S root > /dev/null 2>&1; sleep 3
$A -s $S shell settings put system user_rotation 0
$A -s $S shell 'iptables -F OUTPUT' # open: real servers
$A -s $S install -r -t "$root/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1
$A -s $S shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 12; $A -s $S shell am force-stop $PKG
for f in xonotic-20261107-data.pk3 xonotic-20261107-maps.pk3 font-unifont-20261107.pk3 font-xolonium-20261107.pk3; do $A -s $S push $G/$f $D/ | tail -1; done
$A -s $S push "$root/build/android-cache/slim/data/." $D/ | tail -1
U=$($A -s $S shell stat -c %u /sdcard/Android/data/$PKG/files | tr -d '\r')
$A -s $S shell "chown -R $U:1078 /sdcard/Android/data/$PKG/files; chmod -R ug+rwX /sdcard/Android/data/$PKG/files"
$A -s $S shell am start -S -n $PKG/io.github.dixonsolutions.godnotic.XonoticActivity | tail -1
sleep 45; unanr
uitap text "Got it"; sleep 2; unanr
# the Welcome dialog (first run): fill the name and Continue, by what is on screen, not by coordinates
for k in 1 2 3 4 5 6; do
  if uitap class android.widget.EditText; then sleep 2; $A -s $S shell input text EmuTester; sleep 2; $A -s $S shell input keyevent 4; sleep 1; uitap text Continue && break; fi
  unanr; sleep 5
done
sleep 4; unanr
$A -s $S exec-out screencap -p > "$out/home.png" # Home: the Multiplayer card must show servers, not an empty box
# Multiplayer: tap the card, then wait for the list
t_mp=$(now); uitap text Multiplayer || $A -s $S shell input tap 1590 700
n=0
pick_row() { PICK_MAP="${PICK_MAP:-}" python3 -I - "$out/ui-list.xml" "$root/scripts/mapshots-stock.txt" <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET
stock = set(open(sys.argv[2]).read().split())
nodes = [(n.get("text", ""), n.get("bounds", "")) for n in ET.parse(sys.argv[1]).iter("node")]
def centre(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); a = list(map(int, m.groups())); return (a[0] + a[2]) // 2, (a[1] + a[3]) // 2, a
rows = {}
for t, b in nodes:
    if not b: continue
    x, y, a = centre(b)
    rows.setdefault(round(y / 10), []).append((a[0], t, x, y))
for k in sorted(rows):
    r = sorted(rows[k]); texts = [t for _, t, _, _ in r]
    pl = [t for t in texts if re.fullmatch(r"\d+/\d+", t)]
    want = os.environ.get("PICK_MAP")
    mp = [t for t in texts if (t == want if want else t in stock)]
    if pl and mp and (want or int(pl[0].split("/")[0]) > 0):
        print(r[0][2] if len(r) else 400, r[0][3], mp[0], pl[0], texts[0]); break
PY
}
if [ -z "${ADDR:-}" ]; then
deadline=$(python3 -c "import time;print(time.time()+300)")
pick=""
while [ "$(python3 -c "import time;print(int(time.time()<$deadline))")" = 1 ]; do
  unanr
  $A -s $S shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; $A -s $S pull -q /sdcard/ui.xml "$out/ui-list.xml" 2> /dev/null
  n=$(grep -o 'text="[0-9]*/[0-9]*"' "$out/ui-list.xml" 2> /dev/null | wc -l)
  pick=$(pick_row); [ -n "$pick" ] && break; sleep 2
done
t_list=$(now); log "list: $n rows, picked row after $(ms $t_mp $t_list) ms from the Multiplayer tap (uiautomator polls, slow on a loaded host)"
$A -s $S exec-out screencap -p > "$out/list.png"
log "picked: $pick"
[ -z "$pick" ] && { log "no populated stock-map row: stopping"; ~/.cache/godnotic-work/emu-shot.sh stop; exit 3; }
set -- $pick; px=$1; py=$2; pmap=$3

else
  # a known address instead of the (slow on an emulator) list: type it, open its info popup with the bar's "i"
  sleep 10; unanr
  $A -s $S shell input tap 1207 1146; sleep 1; $A -s $S shell input text "$ADDR"; sleep 1; $A -s $S shell input keyevent 4; sleep 1
  log "address: $ADDR typed (list not used)"; pick="0 0 $ADDR 0/0 typed"
fi
# tap the row: the info popup; frames at ~0.4 s
burst() { # name seconds
  local end=$(python3 -c "import time;print(time.time()+$2)")
  while [ "$(python3 -c "import time;print(int(time.time()<$end))")" = 1 ]; do
    local t=$(now); $A -s $S exec-out screencap -p > "$out/$1-$(ms $t0 $t | awk '{printf "%06d",$1}').png"
  done
}
t0=$(now); if [ -n "${ADDR:-}" ]; then $A -s $S shell input tap 1830 1146; else $A -s $S shell input tap 400 $py; fi
burst popup ${POPUP_SECS:-8}
log "popup frames: $out/popup-*.png (ms since the row tap)"
# Join in the popup (NO_JOIN=1: stop after the popup, for a custom-map server whose packs are not wanted over the metered link)
[ -n "${NO_JOIN:-}" ] && { ~/.cache/godnotic-work/emu-shot.sh stop; log "done (popup only)"; exit 0; }
t0=$(now); uitapw Join || $A -s $S shell input tap 775 1013
burst join 75
log "join frames: $out/join-*.png (ms since the Join tap)"
$A -s $S shell 'find /sdcard/Android/data/'$PKG'/files -type d -name dlcache -exec du -sh {} +' | tee -a "$out/times.txt"
$A -s $S shell 'find /sdcard/Android/data/'$PKG'/files -type d -name dlcache -exec rm -rf {} +'
~/.cache/godnotic-work/emu-shot.sh stop
log "done"
