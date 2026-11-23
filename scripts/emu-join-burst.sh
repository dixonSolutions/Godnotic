#!/usr/bin/env bash
# Type an address into the native Multiplayer screen's field on emulator-5584, tap Join and take a screenshot burst through the join.
#   scripts/emu-join-burst.sh <host:port> <out-dir> [seconds=40]
A=~/Android/Sdk/platform-tools/adb; S=emulator-5584
addr=$1; out=$2; secs=${3:-40}
mkdir -p "$out"; rm -f "$out"/f*.png
$A -s $S shell input tap 1207 1146; sleep 0.5
$A -s $S shell input keyevent 123; for i in $(seq 1 40); do $A -s $S shell input keyevent 67; done
$A -s $S shell input text "$addr"; sleep 0.5
$A -s $S shell input keyevent 4 # hides the keyboard (it is up after typing)
sleep 0.6
( end=$((SECONDS+secs)); i=0; while [ $SECONDS -lt $end ]; do $A -s $S exec-out screencap -p > "$out/f$(printf %03d $i).png"; i=$((i+1)); done ) &
sleep 0.4
$A -s $S shell input tap 1694 1146 # Join
wait
