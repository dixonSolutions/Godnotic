#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Saved Touch preferences: the QuakeC menu registers touch_lan_bots_duel and
# _uib_recent with CF_ARCHIVE (32), so config.cfg keeps them across a restart.
# Run 1 sets them and quits; run 2 starts on the same userdir and must read them back.
#
#   scripts/test-saved-cvars.sh              BASEDIR defaults to <clone>/.suite-base
#   ENGINE=<xonotic binary> BASEDIR=<dir> scripts/test-saved-cvars.sh
set -euo pipefail
cd "$(dirname "$0")/.."
R=$(pwd)
ENGINE=${ENGINE:-$HOME/.cache/godnotic-infra/main/build/bin/xonotic}
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy DP_LINK_JPEG=dlopen
export LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-$HOME/.cache/godnotic-slimtest/lib}
export TMPDIR=${TMPDIR:-$R/.suite-tmp}
mkdir -p "$TMPDIR"

# A basedir of this clone's own, as scripts/run-suites does: the stock pk3s plus this
# clone's pk3dir.
if [ -z "${BASEDIR:-}" ]; then
	BASEDIR=$R/.suite-base
	mkdir -p "$BASEDIR/data"
	rm -f "$BASEDIR"/data/*.pk3
	if [ -e ~/.cache/godnotic-audit/base/data/xonotic-20230620-data.pk3 ]; then
		for f in font-unifont-20230620.pk3 font-xolonium-20230620.pk3 xonotic-20230620-data.pk3 xonotic-20230620-maps.pk3; do
			ln -sfn ~/.cache/godnotic-audit/base/data/$f "$BASEDIR/data/$f"
		done
	else
		for f in ~/.cache/godnotic-infra/repack/src/*.pk3; do ln -sfn "$f" "$BASEDIR/data/$(basename "$f")"; done
	fi
	ln -sfn "$R/engine/data/xonotic-data.pk3dir" "$BASEDIR/data/zz-xonotic-data.pk3dir"
fi
[ -x "$ENGINE" ] || { echo "no engine at $ENGINE" >&2; exit 2; }

UD=$(mktemp -d "$TMPDIR/savecvars.XXXXXX")
trap 'if [ "${status:-1}" -eq 0 ]; then rm -rf "$UD"; fi' EXIT
mkdir -p "$UD/data"
CFG=$UD/data/config.cfg
status=0
ok()  { echo "PASS $*"; }
bad() { echo "FAIL $*"; status=1; }

# run <name> <script-body>: one engine start on $UD; the body is exec'd by the engine.
run() {
	printf '%s\n' "$2" > "$UD/data/$1.cfg"
	( cd "$BASEDIR" && timeout -k 5 150 taskset -c "${CPUS:-0-3}" "$ENGINE" -xonotic -basedir "$BASEDIR" \
		-userdir "$UD" -nosound +vid_fullscreen 0 +vid_width 640 +vid_height 480 \
		+exec xonotic.cfg +set _touch_assets_ready 1 +exec "$1.cfg" ) > "$UD/$1.log" 2>&1 || true
}

echo "== run 1: set the two preferences, then quit"
run first '
defer 8 "set touch_lan_bots_duel 0"
defer 8 "set _uib_recent stormkeep:dm"
defer 8 "touch_lan_bots_duel"
defer 8 "_uib_recent"
defer 11 "quit"
'
[ -f "$CFG" ] && ok "config.cfg written on quit" || bad "no config.cfg after run 1"
grep -qE '^seta "?touch_lan_bots_duel"? "0"' "$CFG" 2>/dev/null \
	&& ok "config.cfg archives touch_lan_bots_duel 0" || bad "config.cfg lacks touch_lan_bots_duel (flag not archived)"
grep -qE '^seta "?_uib_recent"? "stormkeep:dm"' "$CFG" 2>/dev/null \
	&& ok "config.cfg archives _uib_recent" || bad "config.cfg lacks _uib_recent (flag not archived)"

echo "== run 2: start again on the same userdir and read the values back"
run second '
defer 8 "touch_lan_bots_duel"
defer 8 "_uib_recent"
defer 11 "quit"
'
LOG2=$(sed 's/\x1b\[[0-9;]*m//g' "$UD/second.log")
grep -q 'touch_lan_bots_duel is "0"' <<<"$LOG2" \
	&& ok "touch_lan_bots_duel is 0 after restart" || bad "touch_lan_bots_duel did not survive: $(grep -o 'touch_lan_bots_duel is [^[]*' <<<"$LOG2" | head -1)"
grep -q '_uib_recent is "stormkeep:dm"' <<<"$LOG2" \
	&& ok "_uib_recent is stormkeep:dm after restart" || bad "_uib_recent did not survive: $(grep -o '_uib_recent is [^[]*' <<<"$LOG2" | head -1)"

if [ "$status" -ne 0 ]; then
	echo "logs kept in $UD" >&2
fi
exit $status
