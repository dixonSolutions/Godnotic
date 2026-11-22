#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The Linux app's session behaviour with a stand-in engine (tests/linux-session/stub-engine.py)
# as its UI backend, on a private Xvfb: what the desktop shows when a join is asked for, while
# the match runs and after the engine ends. Fixes for the owner's report (1289: several
# windows, a join that did nothing or quit, "Resume game" after the game was gone):
#
#   join      a join (a godnotic:// link, a typed address) goes to the running engine over the
#             bridge; no second engine is started (the stub counts its starts)
#   one       while the match runs the app window is hidden: no toplevel of the app on screen
#   crash     the engine ends with an error: one window again, "Start again", and no Resume
#             or pause page left from the match
#   quit      the engine exits cleanly after a real session: the app quits with it
#
#   scripts/test-linux-session.sh      (needs loader/target/release/godnotic-loader:
#                                       loader/build.sh, Xvfb and org.gnome.Platform//48)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${GODNOTIC_LOADER_BIN:-$ROOT/loader/target/release/godnotic-loader}"
if [ ! -x "$BIN" ] || ! command -v Xvfb > /dev/null || ! flatpak info org.gnome.Platform//48 > /dev/null 2>&1; then
	echo "SKIP: needs $BIN (loader/build.sh), Xvfb and org.gnome.Platform//48"
	exit 0
fi
# under home: the Flatpak sandbox does not see the host's /tmp
WORK="$(mktemp -d "$HOME/.cache/godnotic-session-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

run() { # name end -> $WORK/<name>/ with the stub's files, counts.txt, labels-*.txt, log.txt
	local name=$1 end=$2 out="$WORK/$1"
	mkdir -p "$out"
	cat > "$out/run.script" << SCRIPT
wait 2500
labels $out/labels-home.txt
count $out/counts.txt
join 127.0.0.1:26111
wait 3000
count $out/counts.txt
labels $out/labels-game.txt
wait 9000
count $out/counts.txt
labels $out/labels-after.txt
click stopped:again
wait 3000
count $out/counts.txt
labels $out/labels-again.txt
wait 1000
quit
SCRIPT
	LIVE=1 DRIVE_ENV="GODNOTIC_START=$ROOT/tests/linux-session/stub-engine.py STUB_OUT=$out STUB_FIXTURES=$ROOT/tests/uibridge/fixtures STUB_END=$end STUB_LIVE=4" \
		timeout 120 "$ROOT/loader/scripts/drive.sh" "$out/run.script" "$out" 1280x800 dark > "$out/log.txt" 2>&1
}

run crash crash
o="$WORK/crash"
[ "$(cat "$o/joined" 2> /dev/null)" = "127.0.0.1:26111" ] && ok "join: the address went to the running engine over the bridge" \
	|| bad "join: the engine got no address ($(cat "$o/joined" 2> /dev/null))"
# the first start, and the one "Start again" asks for below: a join starts no engine of its own
[ "$(wc -l < "$o/starts" 2> /dev/null)" = 2 ] && ok "join: no second engine started for the join (two starts: the app's, and Start again)" \
	|| bad "join: $(wc -l < "$o/starts" 2> /dev/null) engine starts, expected 2"
[ "$(sed -n 1p "$o/counts.txt" 2> /dev/null)" = 1 ] && ok "one: Home is one window" || bad "one: Home windows: $(sed -n 1p "$o/counts.txt")"
[ "$(sed -n 2p "$o/counts.txt" 2> /dev/null)" = 0 ] && ok "one: in the match the app window is hidden (the engine's is the one)" \
	|| bad "one: in the match the app shows $(sed -n 2p "$o/counts.txt") windows"
[ "$(sed -n 3p "$o/counts.txt" 2> /dev/null)" = 1 ] && grep -qxF "Start again" "$o/labels-after.txt" \
	&& ok "crash: one window again, with Start again" \
	|| bad "crash: windows $(sed -n 3p "$o/counts.txt"), labels $(tr '\n' '|' < "$o/labels-after.txt" | cut -c1-160)"
if grep -qiE "^(Resume|Pause|Back to game)$" "$o/labels-after.txt" "$o/labels-game.txt" 2> /dev/null; then
	bad "crash: a Resume/Pause page is on screen ($(tr '\n' '|' < "$o/labels-after.txt" | cut -c1-160))"
else
	ok "crash: no Resume or pause page after the engine is gone"
fi

[ "$(sed -n 4p "$o/counts.txt" 2> /dev/null)" = 1 ] && grep -qxF "Singleplayer" "$o/labels-again.txt" \
	&& ok "crash: Start again brings Home back in one window" \
	|| bad "crash: after Start again windows $(sed -n 4p "$o/counts.txt"), labels $(tr '\n' '|' < "$o/labels-again.txt" | cut -c1-160)"

run quit quit
o="$WORK/quit"
# the app quit before its script reached the third count
if grep -q "the engine exited" "$o/log.txt" && [ "$(wc -l < "$o/counts.txt")" = 2 ]; then
	ok "quit: a clean exit after a real session closes the app"
else
	bad "quit: counts $(tr '\n' ' ' < "$o/counts.txt"); log $(grep -a "engine exited\|godnotic-loader" "$o/log.txt" | head -3 | tr '\n' '|')"
fi

[ $fail = 0 ] && echo "linux-session: all passed"
exit $fail
