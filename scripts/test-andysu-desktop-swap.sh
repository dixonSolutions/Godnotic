#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The Android live engine swap, run on the desktop: the real resident loader
# (android/app/src/main/cpp/andysu/andysu_loader.c, with tests/andysu/desktop/shim for the two
# Android headers it uses) runs a desktop build of the engine library with the swap hooks
# (-DANDYSU_SWAP, andysu_engine.c, the same version script as Android). Once the first engine has
# drawn its menu for a while, the harness asks the loader for a swap (what EngineSwap's
# nativeRequestSwap does on Android) to a second engine build. Checked: the second engine starts
# in the same process (no exit, no relaunch), gets the first one's state, brings the menu back,
# draws frames and reports healthy; the first engine's shutdown leaves no thread behind that
# touches its freed memory (the process lives on and quits cleanly). Then the in-process
# rollback (the new engine fails its start; the previous engine comes back; FAIL=0 skips it), and
# an engine built for another Java side (java_abi), which the loader must refuse (ABI=0 skips it).
#
#   BASEDIR=<basedir with data/> scripts/test-andysu-desktop-swap.sh
# Needs the engine's build dependencies (sdl2-config on PATH) and a JDK's jni.h (JAVA_HOME).
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
: "${BASEDIR:?BASEDIR: a basedir with the game data in data/}"
out=${OUT:-$(mktemp -d "${TMPDIR:-/tmp}/desktop-swap.XXXXXX")}
mkdir -p "$out"
andy=$root/android/app/src/main/cpp/andysu
exe=libmain-swaptest.so
mk() { make -s --no-print-directory -C engine/darkplaces DP_LINK_JPEG=dlopen CFLAGS_SDL="$(sdl2-config --cflags)" "$@"; }
cflags=$(mk --eval 'print-cflags: ; @echo $(CFLAGS_COMMON) $(CFLAGS_CLIENT) $(CFLAGS_RELEASE) $(OPTIM_RELEASE)' print-cflags)
ldflags=$(mk --eval 'print-ld: ; @echo $(LDFLAGS_SDL)' print-ld)
# andysu_engine.c with exactly the engine's flags (it reads engine structs), once per build id
for id in a b c; do
	extra=; [ $id = c ] && extra=-DGODNOTIC_JAVA_ABI=99 # an engine for another Java side
	(cd engine/darkplaces && cc -c $cflags -fPIC -DANDYSU_SWAP -DANDYSU_BUILD_ID="\"desktop-$id\"" $extra -I. -I"$andy" \
		"$andy/andysu_engine.c" -o "$out/andysu_engine_$id.o")
done
cc -c -fPIC -O2 tests/andysu/desktop/engine_stub.c -o "$out/engine_stub.o"
# the engine objects, position independent, with the swap hooks; linked as build a
ldlib="-shared -Wl,--version-script=$andy/andysu_engine.map -Wl,-Bsymbolic -Wl,-z,defs"
mk -j"${JOBS:-4}" sdl-release EXE_SDL=$exe STRIP=true CFLAGS_EXTRA="-fPIC -DANDYSU_SWAP -I$andy" \
	LDFLAGS_RELEASE="-O3 $ldlib $out/andysu_engine_a.o $out/engine_stub.o"
mv engine/darkplaces/$exe "$out/libmain.so"
objs=$(ls engine/darkplaces/build-obj/release/$exe/*.o)
for id in b c; do
	cc -fPIC -o "$out/libmain-$id.so" engine/darkplaces/builddate.c -DBUILDTYPE=release $objs "$out/andysu_engine_$id.o" \
		"$out/engine_stub.o" -O3 $ldlib $ldflags
done
cc -O2 -g -D_GNU_SOURCE -Wno-incompatible-pointer-types -I tests/andysu/desktop/shim -I"${JAVA_HOME:-$HOME/.local/share/java/jdk-21}/include" \
	-I"${JAVA_HOME:-$HOME/.local/share/java/jdk-21}/include/linux" \
	tests/andysu/desktop/swap_harness.c -o "$out/swap-harness" -ldl -lpthread
echo "built $out/libmain.so, $out/libmain-b.so, $out/swap-harness"

home=$out/home; rm -rf "$home"; mkdir -p "$home/data"
cat > "$home/data/swaptest.cfg" <<'CFG'
seta touch_profile_done 1
seta cl_welcome 0
defer 60 "screenshot"
defer 75 "quit"
CFG
# No network: the menu would otherwise query the public server list. unshare -rn gives the run
# a network namespace of its own where user namespaces are allowed.
nonet=; unshare -rn true 2>/dev/null && nonet="unshare -rn"
run() { # name, extra env
	local name=$1; shift
	env SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy SWAP_TO="$out/libmain-b.so" "$@" \
		timeout "${RUN_TIMEOUT:-400}" $nonet "$out/swap-harness" -xonotic -basedir "$BASEDIR" -userdir "$home" +exec swaptest.cfg \
		> "$out/$name.log" 2>&1
}
fail=0
check() { if grep -aq -- "$2" "$out/$1.log"; then echo "ok   $1: $3"; else echo "FAIL $1: $3 (no '$2')"; fail=1; fi; }
nocheck() { if grep -aq -- "$2" "$out/$1.log"; then echo "FAIL $1: $3 ($(grep -m1 -- "$2" "$out/$1.log" | cut -c1-160))"; fail=1; else echo "ok   $1: $3"; fi; }

set +e; run swap; rc=$?; set -e
echo "swap run rc=$rc (log $out/swap.log)"
check swap '"event":"engine_entry","build_id":"desktop-a","swapped":0' "the first engine started"
check swap '"event":"engine_swap_begin"' "the loader accepted the swap request"
check swap '"event":"engine_state_saved"' "the first engine saved its state"
check swap '"event":"engine_stopped"' "the first engine shut down without exiting"
check swap '"event":"engine_entry","build_id":"desktop-b","swapped":1' "the second engine started in the same process"
check swap '"event":"engine_state_restored","build_id":"desktop-b"' "the second engine took over the state"
check swap '"menu":1' "the menu was on screen and comes back"
check swap '"event":"engine_frames","build_id":"desktop-b"' "the second engine draws frames"
check swap '"event":"engine_healthy"' "the second engine reported healthy"
nocheck swap 'Segmentation fault\|SIGSEGV\|Aborted\|double free\|Engine Error' "no crash"
pids=$(grep -ao '"pid":[0-9]*' "$out/swap.log" | sort -u | wc -l)
if [ "$pids" = 1 ]; then echo "ok   swap: one process id throughout"; else echo "FAIL swap: $pids process ids"; fail=1; fi
grep -a '\[harness\] threads' "$out/swap.log" | sed 's/^/     /'
if [ "$rc" = 0 ]; then echo "ok   swap: the second engine quit cleanly (rc 0)"; else echo "FAIL swap: exit status $rc"; fail=1; fi
shots=$(find "$home/data/screenshots" -name '*.jpg' -newer "$out/swap-harness" 2>/dev/null | wc -l)
if [ "$shots" -ge 1 ]; then echo "ok   swap: the second engine took its screenshot ($shots)"; else echo "FAIL swap: no screenshot after the swap"; fail=1; fi

if [ "${FAIL:-1}" = 1 ]; then
	set +e; run rollback SWAP_FAIL_INIT=1; rc=$?; set -e
	echo "rollback run rc=$rc (log $out/rollback.log)"
	check rollback '"event":"engine_start_failed"' "the new engine gave up its start"
	check rollback '"event":"engine_rolled_back"' "the loader went back to the previous engine"
	check rollback '"event":"loader_engine_loaded",.*"build_id":"desktop-a","swapped":2' "the previous engine runs again in the same process"
	check rollback '"event":"engine_frames","build_id":"desktop-a"' "and draws frames"
	nocheck rollback 'Segmentation fault\|SIGSEGV\|Aborted\|double free' "no crash"
	if [ "$rc" = 0 ]; then echo "ok   rollback: quit cleanly"; else echo "FAIL rollback: exit status $rc"; fail=1; fi
fi
if [ "${ABI:-1}" = 1 ]; then
	set +e; run abi SWAP_TO="$out/libmain-c.so"; rc=$?; set -e
	echo "abi run rc=$rc (log $out/abi.log)"
	check abi 'swap refused: java_abi 99 != running' "an engine built for another Java side is refused before anything stops"
	nocheck abi '"event":"engine_stopped"' "the running engine was not touched"
	check abi '"event":"engine_frames","build_id":"desktop-a","frames":2400' "and keeps drawing"
	if [ "$rc" = 0 ]; then echo "ok   abi: quit cleanly"; else echo "FAIL abi: exit status $rc"; fail=1; fi
fi
[ "$fail" = 0 ] && echo "desktop live swap passed" || { echo "desktop live swap FAILED (logs in $out)"; exit 1; }
