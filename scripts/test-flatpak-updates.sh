#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Settings > Updates in the Flatpak, the Linux app's half (loader/crates/gtk/src/updates.rs),
# end to end, offscreen and offline: the real godnotic-loader in the GNOME runtime on a
# private Xvfb, a private session bus with a stand-in Flatpak portal
# (tests/flatpakupd/fake_portal.py), and a local web server in place of the Flatpak
# remote (an OSTree summary naming a newer commit) and the release changelog.
#
#   1. The app owns update-status.txt: via=flatpak, portal=yes, the installed version.
#   2. check   -> it reads the remote's summary and the changelog: available, 1.2.206.
#   3. install -> the portal's Update; Progress becomes downloading with a percentage,
#                 then ready (Restart to finish).
#   4. restart -> the portal's Spawn with the latest-version flag, argv godnotic-loader,
#                 GODNOTIC_RESTARTED=1; then the app quits.
#   5. No portal on the bus: state managed, portal=none, the reason (the menu keeps the
#      package-manager text).
#
#   scripts/test-flatpak-updates.sh    (needs loader/target/release/godnotic-loader from
#                                       loader/build.sh, Xvfb, org.gnome.Platform//50,
#                                       dbus-daemon and python3-gi)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${GODNOTIC_LOADER_BIN:-$ROOT/loader/target/release/godnotic-loader}"
RUNTIME="${GODNOTIC_TEST_RUNTIME:-org.gnome.Platform//50}"
if [ ! -x "$BIN" ] || ! command -v Xvfb > /dev/null || ! command -v dbus-daemon > /dev/null \
	|| ! flatpak info "$RUNTIME" > /dev/null 2>&1 || ! python3 -c 'import gi' 2> /dev/null; then
	echo "SKIP: needs $BIN (loader/build.sh), Xvfb, dbus-daemon, python3-gi and $RUNTIME"
	exit 0
fi
# under home: the Flatpak sandbox does not see the host's /tmp
WORK="$(mktemp -d "$HOME/.cache/godnotic-flatpakupd-test.XXXXXX")"
PIDS=()
stop_apps() { # the sandboxed app outlives the host-side wrapper when flatpak is distrobox-host-exec
	pgrep -f -- "DBUS_SESSION_BUS_ADDRESS=unix:path=$WORK/bus" | while read -r p; do kill "$p" 2> /dev/null; done
}
cleanup() {
	stop_apps
	for p in "${PIDS[@]}"; do kill "$p" 2> /dev/null; done
	wait 2> /dev/null
	[ -n "${KEEP:-}" ] && { echo "kept $WORK"; return; }
	rm -rf "$WORK"
}
trap cleanup EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

OLD=03c3d5707b0f186f2023d3e0ba1811bbb79539d4c9ca7c810862a30610e2e615
NEW=6909a2fc6c979219dde636c8d242cb8d67efc93046f5ec66f41b1b83651ce731
REF=app/io.github.dixonSolutions.Godnotic/x86_64/master

# the bus, no service files: nothing real is activated
cat > "$WORK/bus.conf" <<EOF
<busconfig><type>session</type><listen>unix:path=$WORK/bus</listen><auth>EXTERNAL</auth>
<policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy></busconfig>
EOF
dbus-daemon --nofork --nopidfile --config-file="$WORK/bus.conf" & PIDS+=($!)
for _ in $(seq 50); do [ -S "$WORK/bus" ] && break; sleep 0.1; done
ADDR="unix:path=$WORK/bus"

# the remote and the changelog
mkdir -p "$WORK/www/flatpak"
python3 "$ROOT/tests/flatpakupd/fake_portal.py" summary "$REF=$NEW" "app/io.github.dixonSolutions.Godnotic/aarch64/master=$OLD" > "$WORK/www/flatpak/summary"
printf '1.2.206\n- Faster joins\n\n1.2.205\n- Earlier\n' > "$WORK/www/changelog.txt"
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK/www" > "$WORK/http.log" 2>&1 & PIDS+=($!)

# what the sandbox would say about this instance, and an install root with a version
printf '[Application]\nname=io.github.dixonSolutions.Godnotic\n\n[Instance]\napp-commit=%s\narch=x86_64\nbranch=master\nflatpak-version=1.18.4\n' "$OLD" > "$WORK/flatpak-info"
mkdir -p "$WORK/app/bin" "$WORK/app/share/xonotic" "$WORK/user" "$WORK/home"
printf '#!/bin/sh\nexit 0\n' > "$WORK/app/bin/start.sh"; chmod +x "$WORK/app/bin/start.sh"
echo 1.2.205 > "$WORK/app/share/xonotic/version.txt"
STATUS="$WORK/user/data/touch/update-status.txt"
REQ="$WORK/user/data/touch/update-request.txt"

# flatpak run sets DBUS_SESSION_BUS_ADDRESS itself (--env cannot override it): env does, inside
app() { # log
	"$ROOT/loader/scripts/run-headless.sh" --size 1280x900 -- flatpak run --filesystem=home --share=network \
		--env=HOME="$WORK/home" --env=GODNOTIC_NON_UNIQUE=1 \
		--env=GODNOTIC_TEST_UPDATES=1 --env=GODNOTIC_FLATPAK_INFO="$WORK/flatpak-info" \
		--env=GODNOTIC_APP_ROOT="$WORK/app" --env=GODNOTIC_USER_BASE="$WORK/user" \
		--env=GODNOTIC_FLATPAK_REMOTE="http://127.0.0.1:$PORT/flatpak" \
		--env=GODNOTIC_CHANGELOG_URL="http://127.0.0.1:$PORT/changelog.txt" \
		--command=env "$RUNTIME" DBUS_SESSION_BUS_ADDRESS="$ADDR" "$BIN" --fixture=settings-updates-linux \
		--fixtures-dir="$ROOT/tests/uibridge/fixtures" --size=1100x760 > "$1" 2>&1
}
line() { sed -n "${1}p" "$STATUS" 2> /dev/null; }
key() { sed -n "s/^$1=//p" "$STATUS" 2> /dev/null; }
until_() { # seconds what condition...
	local secs=$1 what=$2; shift 2
	for _ in $(seq $((secs * 10))); do "$@" && return 0; sleep 0.1; done
	return 1
}
st_is() { [ "$(line 2)" = "$1" ]; }
ask() { mkdir -p "$(dirname "$REQ")"; echo "$1" > "$REQ"; }

# ---- 1-4 with the portal
python3 "$ROOT/tests/flatpakupd/fake_portal.py" serve "$ADDR" "$WORK/portal.log" & PIDS+=($!)
until_ 10 portal grep -q '"ready"' "$WORK/portal.log" 2> /dev/null || bad "the stand-in portal did not start"
app "$WORK/app.log" & APP=$!; PIDS+=($APP)

owned() { [ "$(key portal)" = yes ] && [ "$(key via)" = flatpak ] && [ "$(line 3)" = 1.2.205 ]; }
if until_ 60 status owned; then
	ok "the app owns the status: via=flatpak, portal=yes, installed 1.2.205 ($(line 2))"
else
	bad "no portal status: $(tr '\n' '|' < "$STATUS" 2> /dev/null) $(tail -3 "$WORK/app.log")"
fi
# the monitor can come a moment after the status file says portal=yes
until_ 10 monitor grep -q '"call": "CreateUpdateMonitor"' "$WORK/portal.log" && ok "it made an update monitor" || bad "no CreateUpdateMonitor call"

ask check
checked() { st_is available && [ "$(line 4)" = 1.2.206 ] && [ "$(key checked_result)" = available ]; }
if until_ 30 check checked; then
	ok "check: the remote's summary names a newer commit; the changelog names 1.2.206"
else
	bad "check: $(tr '\n' '|' < "$STATUS")"
fi
grep -q 'GET /flatpak/summary' "$WORK/http.log" && grep -q 'GET /changelog.txt' "$WORK/http.log" \
	&& ok "check: it fetched the summary and the changelog, nothing else ($(grep -c GET "$WORK/http.log") requests)" \
	|| bad "check: requests $(cat "$WORK/http.log")"

ask install
moving() { st_is downloading && [ "$(line 6)" -gt 0 ] 2> /dev/null; }
if until_ 15 updating moving; then
	ok "install: the portal's Update; progress $(line 6)% (step $(key step) of $(key steps))"
else
	bad "install: no progress: $(tr '\n' '|' < "$STATUS")"
fi
if until_ 15 ready st_is ready; then ok "install: done, Restart to finish (ready, $(line 4))"; else bad "install: not ready: $(tr '\n' '|' < "$STATUS")"; fi
grep -q '"call": "Update"' "$WORK/portal.log" && ok "install: one Update call ($(grep -c '"call": "Update"' "$WORK/portal.log"))" || bad "install: no Update call"

ask restart
if until_ 10 spawn grep -q '"call": "Spawn"' "$WORK/portal.log"; then
	sp=$(grep '"call": "Spawn"' "$WORK/portal.log" | head -1)
	python3 - "$sp" <<'PY' && ok "restart: Spawn of the newest build (godnotic-loader, latest-version flag, GODNOTIC_RESTARTED=1)" || bad "restart: Spawn $sp"
import json, sys
s = json.loads(sys.argv[1])
sys.exit(0 if s["argv"] == ["godnotic-loader"] and s["flags"] & 2 and s["env"].get("GODNOTIC_RESTARTED") == "1" and s["cwd"] == "/" else 1)
PY
else
	bad "restart: no Spawn call"
fi
if until_ 10 quit sh -c "! kill -0 $APP 2> /dev/null"; then ok "restart: the old app quit"; else bad "restart: the old app is still running"; fi
grep -q '"call": "Close"' "$WORK/portal.log" && ok "restart: the monitor was closed" || bad "restart: no Close"

# ---- 5 without a portal
stop_apps
kill "${PIDS[2]}" 2> /dev/null
rm -f "$STATUS"
app "$WORK/app2.log" & APP=$!; PIDS+=($APP)
noportal() { [ "$(key portal)" = none ] && st_is managed && grep -q 'Flatpak 1.6' "$STATUS"; }
if until_ 60 noportal noportal; then
	ok "no portal: state managed, portal=none, '$(line 7)'"
else
	bad "no portal: $(tr '\n' '|' < "$STATUS" 2> /dev/null)"
fi
ask check
sleep 2
[ ! -e "$REQ" ] && st_is managed && ok "no portal: a check request is taken and ignored" || bad "no portal: $(line 2), request left: $(cat "$REQ" 2> /dev/null)"
kill "$APP" 2> /dev/null
stop_apps

[ "$fail" = 0 ] && echo "test-flatpak-updates: all passed" || echo "test-flatpak-updates: FAILED"
exit $fail
