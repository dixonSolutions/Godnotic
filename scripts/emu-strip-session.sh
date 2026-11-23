#!/bin/bash
# One Android emulator session for the weapon strip (run it under ~/.cache/godnotic-work/emu-slot.sh): installs the debug APK, pushes
# the game data, then on REAL touch input (adb input) checks: a weapon row on a stock public server switches on the first tap; the
# HOOK entry on a public hook server holds the hook and lets go; in a local g_nades match a nade throw, then the waiting NADE row's
# top edge does not fire the hook. Frames: build/emu-*.jpg (<=1600 px).   PHASE=a,b,c to choose phases.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
G=$HOME/.cache/godnotic-work
ADB="timeout 30 $HOME/Android/Sdk/platform-tools/adb -s $ANDROID_SERIAL"
PKG=io.github.dixonsolutions.godnotic.mplist
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk
EXT=/sdcard/Android/data/$PKG/files
DATA=$ROOT/.suite-base/data
OUT=$ROOT/build; mkdir -p "$OUT"
PHASES=${PHASE:-a,b,c}
SERVER_STOCK=${SERVER_STOCK:-72.205.209.239:26000}; SERVER_HOOK=${SERVER_HOOK:-66.42.118.210:26000}
trap '"$G/emu-shot.sh" stop' EXIT
log() { echo "[$(date +%T)] $*"; }
shot() { # name
  $ADB exec-out screencap -p > "$OUT/emu-$1.png" && python3 - "$OUT/emu-$1.png" "$OUT/emu-$1.jpg" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); im.thumbnail((1600, 1600)); im.save(sys.argv[2], quality=85)
PY
  rm -f "$OUT/emu-$1.png"; log "frame $OUT/emu-$1.jpg"
}
cmd() { # console line(s) to the running engine through the poll cfg (runs every 0.3 s until replaced)
  printf '%s\n' "$@" > "$OUT/.xtcmd.cfg"; $ADB push "$OUT/.xtcmd.cfg" $EXT/data/xtcmd.cfg > /dev/null
}
quiet_cfg() { printf '%s\n' 'set cl_touch_practice_pilot 0' 'set cl_touch_autoshoot 0' 'set cl_touch_aimassist 0' 'set cl_touch_ledgeguard 0' 'set cl_touch_autododge 0' 'set cl_welcome 0' 'set touch_always_autojoin 0' 'set cl_touch_hook_rescue 0' 'set cl_touch_nade_refire 25' 'set developer 0' 'alias xt_poll "exec xtcmd.cfg; defer 0.3 xt_poll"' 'xt_poll'; }
condump_get() { # file -> stdout
  $ADB shell 'for d in '$EXT'/userdata/data '$EXT'/userdata/data/data; do [ -f $d/'$1' ] && cat $d/'$1'; done' | sed 's/\x1b\[[0-9;]*m//g'
}
launch() { # name "args"
  $ADB shell am force-stop $PKG; sleep 2
  quiet_cfg > "$OUT/.xtboot.cfg"; $ADB push "$OUT/.xtboot.cfg" $EXT/data/xtboot.cfg > /dev/null
  : > "$OUT/.xtcmd.cfg"; $ADB push "$OUT/.xtcmd.cfg" $EXT/data/xtcmd.cfg > /dev/null
  printf '%s\n' '+exec' 'xtboot.cfg' $2 > "$OUT/.xonotic.args"; $ADB push "$OUT/.xonotic.args" $EXT/xonotic.args > /dev/null
  $ADB shell am start -n $PKG/io.github.dixonsolutions.godnotic.GodnoticBootActivity > /dev/null
  log "launched $1"
}
join_play() { # waits, then joins the match
  for t in 1 2 3 4 5 6; do cmd "touch_join spectate" "togglemenu 0"; sleep 4; done
  cmd "touch_join auto"; sleep 8; cmd ""
}
strip_info() { # -> sets X W0Y ROWH HOOKTOP HOOKBOT NADETOP NADEBOT N HOOK NADE
  rm -f "$OUT/.strip.txt"; $ADB shell 'rm -f '$EXT'/userdata/data/strip.txt '$EXT'/userdata/data/data/strip.txt' > /dev/null
  cmd "_touch_test_strip" "condump strip.txt"; sleep 2; cmd ""
  condump_get strip.txt | grep -a '^STRIP' | tail -1 | tee "$OUT/.strip.txt"
  eval "$(sed -E 's/^STRIP //; s/ +/\n/g' "$OUT/.strip.txt" | sed -E '/=/!d; s/^([a-z0-9]+)=(.*)$/S_\1=\2/')"
}
px() { awk -v v="$1" -v s="$2" 'BEGIN{printf "%d", v*s}'; }
SW=1920; SH=1200

log "start emulator"; "$G/emu-shot.sh" start
log "install"; timeout 120 $HOME/Android/Sdk/platform-tools/adb -s $ANDROID_SERIAL install -r "$APK" | tail -1
# the dirs must be the app's to write: let the app create files/ itself, then add ours inside (shell-made dirs gave EACCES)
$ADB shell am start -n $PKG/io.github.dixonsolutions.godnotic.GodnoticBootActivity > /dev/null; sleep 8; $ADB shell am force-stop $PKG
$ADB shell "ls -ld $EXT $EXT/data 2>&1; mkdir -p $EXT/data $EXT/userdata/data; chmod -R 777 $EXT 2>&1 | head -2; ls -ld $EXT/data"
log "push data"
for f in font-unifont-20261107.pk3 font-xolonium-20261107.pk3 xonotic-20261107-data.pk3 xonotic-20261107-maps.pk3 xonotic-20261107-xoncompat.pk3 xonotic-20261107-nexcompat.pk3; do
  timeout 600 $HOME/Android/Sdk/platform-tools/adb -s $ANDROID_SERIAL push "$(readlink -f $DATA/$f)" $EXT/data/$f > /dev/null
done
for f in csprogs.dat menu.dat progs.dat; do timeout 120 $HOME/Android/Sdk/platform-tools/adb -s $ANDROID_SERIAL push "$ROOT/engine/data/xonotic-data.pk3dir/$f" $EXT/userdata/data/$f > /dev/null; done
$ADB shell dumpsys window | grep -m1 -i 'mCurrentFocus' || true

case ",$PHASES," in *,a,*)
  log "== A: real stock server $SERVER_STOCK"
  launch stock "+connect $SERVER_STOCK"; sleep 45; shot a0-loading
  join_play; sleep 5; shot a1-joined
  strip_info
  Y0=$(awk -v a="$S_w0y" 'BEGIN{printf "%.4f", a}')
  # select the first weapon row: one tap
  X=$(px $S_x $SW); Y=$(px $S_w0y $SH)
  $ADB shell input tap $X $Y; sleep 1.2; shot a2-after-tap-row1
  # then the second row (one tap)
  Y2=$(awk -v a="$S_w0y" -v r="$S_rowh" -v h=$SH 'BEGIN{printf "%d", (a+r)*h}')
  $ADB shell input tap $X $Y2; sleep 1.2; shot a3-after-tap-row2
;; esac

case ",$PHASES," in *,b,*)
  log "== B: real hook server $SERVER_HOOK"
  launch hook "+connect $SERVER_HOOK"; sleep 50; shot b0-loading
  join_play; sleep 4; shot b1-joined
  strip_info
  X=$(px $S_x $SW); Y=$(awk -v a="$S_w0y" -v r="$S_rowh" -v n="$S_n" -v h=$SH 'BEGIN{printf "%d", (a+r*n)*h}')
  ( $ADB shell input swipe $X $Y $X $Y 2500 & ); sleep 1.0
  cmd "touch_hook_status" "condump hookheld.txt"; shot b2-hook-held-finger-down; sleep 2.5; cmd ""
  condump_get hookheld.txt | grep -a 'touch hook:' | tail -2 | cut -c1-120
  sleep 1; cmd "touch_hook_status" "condump hookup.txt"; sleep 1.5; shot b3-after-release; cmd ""
  condump_get hookup.txt | grep -a 'touch hook:' | tail -1 | cut -c1-120
;; esac

case ",$PHASES," in *,c,*)
  log "== C: local g_nades match"
  launch nades "+set sv_cheats 1 +set g_nades 1 +set bot_number 2 +set skill 1 +set g_warmup 0 +map stormkeep"; sleep 40; shot c0-loading
  join_play; cmd "cmd give all"; sleep 3; cmd ""; shot c1-joined
  strip_info
  X=$(px $S_x $SW); YN=$(awk -v a="$S_nadetop" -v b="$S_nadebot" -v h=$SH 'BEGIN{printf "%d", (a+b)/2*h}')
  ( $ADB shell input swipe $X $YN $X $YN 1500 & ); sleep 0.8; shot c2-nade-cooking; sleep 1.5
  shot c3-after-throw
  YT=$(awk -v a="$S_nadetop" -v h=$SH 'BEGIN{printf "%d", a*h+5}')
  cmd "touch_nade_status" "touch_hook_status" "condump nadewait.txt"; sleep 1.5; cmd ""
  condump_get nadewait.txt | grep -a 'touch nade:\|touch hook:' | tail -2 | cut -c1-130
  $ADB shell input tap $X $YT; sleep 0.8
  cmd "touch_hook_status" "touch_nade_status" "condump nadetap.txt"; sleep 1.5; shot c4-after-waiting-top-edge-tap; cmd ""
  condump_get nadetap.txt | grep -a 'touch hook:\|touch nade:' | tail -2 | cut -c1-130
;; esac
log done
