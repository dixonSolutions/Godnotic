#!/bin/bash
# Joins over a slow, lossy link must get in on the first try (owner,
# 2026-11-20: "high-ping servers often need several join attempts").
#
# A dedicated server in one network namespace, the client in another (unshare
# -rn, as test-net-away.sh), and tests/net/udp_proxy.py between them adding a
# round trip of 2*DELAY seconds, up to JITTER more each way (packets overtake
# each other) and LOSS of the packets in both directions. JOINS fresh client
# processes join one after another, each through a fresh proxy (a new source
# port, as a new device); a join passes when the player is in the game within
# JOIN_TIMEOUT seconds on its first connection: no "Connect: failed", no
# automatic reconnect, no second accept.
#
#   BASEDIR=<Godnotic basedir> scripts/test-net-highping.sh
# ENGINE, SERVER (default engine/darkplaces/darkplaces-sdl and
# darkplaces-dedicated; a stock upstream dedicated server is the realistic
# peer), JOINS (20), DELAY (0.2), JITTER (0.1), LOSS (0.03), JOIN_TIMEOUT
# (120), MAPCHANGE_JOIN (3: the server changes map during that join's csprogs
# download, as at a match end; 0 = never), MAP (stormkeep), OUT (keep the logs), NET_TRACE=1 (packet traces),
# REORDER=1 (jittered packets overtake each other), SV_BASEDIR (the server's; a stock engine cannot read zstd-packed pk3s, so give it
# plain ones), CL_ARGS (more client command-line settings).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
SERVER="${SERVER:-$ROOT/engine/darkplaces/darkplaces-dedicated}"
BASEDIR="${BASEDIR:?set BASEDIR}"
SV_BASEDIR="${SV_BASEDIR:-$BASEDIR}"
MAP="${MAP:-stormkeep}"
MAPCHANGE_JOIN="${MAPCHANGE_JOIN:-3}" JOINS="${JOINS:-20}" DELAY="${DELAY:-0.2}" JITTER="${JITTER:-0.1}" LOSS="${LOSS:-0.03}" JOIN_TIMEOUT="${JOIN_TIMEOUT:-120}"

if [ -z "${NET_TEST_INSIDE:-}" ]; then
	unshare -rn true 2> /dev/null || { echo "SKIP: no unprivileged network namespaces"; exit 77; }
	exec unshare -rn env NET_TEST_INSIDE=1 bash "$0" "$@"
fi
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-highping.XXXXXX")"
cleanup() { jobs -p | xargs -r kill 2> /dev/null; [ -n "${OUT:-}" ] && mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

ip link set lo up
unshare -n sleep 100000 & NSPID=$!
sleep 0.3
ip link add cl0 type veth peer name sv0
ip link set sv0 netns "$NSPID"
ip addr add 10.88.0.1/24 dev cl0
ip link set cl0 up
nsenter -t "$NSPID" -n sh -c 'ip link set lo up; ip addr add 10.88.0.2/24 dev sv0; ip link set sv0 up'

mkdir -p "$WORK/sv/data"
# the server runs next.cfg every half second: the test writes commands into it
printf 'alias _sp "exec next.cfg; defer 0.5 _sp"\n_sp\n' > "$WORK/sv/data/svpoll.cfg"
: > "$WORK/sv/data/next.cfg"
# shellcheck disable=SC2086
nsenter -t "$NSPID" -n "$SERVER" -dedicated -xonotic -basedir "$SV_BASEDIR" -userdir "$WORK/sv" +set port 26000 +set sv_public 0 \
	+set bot_number 2 +set minplayers 0 ${NET_TRACE:++set touch_net_trace $NET_TRACE} +map "$MAP" +exec svpoll.cfg > "$WORK/server.log" 2>&1 &
SVPID=$!
for _ in $(seq 1 300); do grep -q "Server spawned" "$WORK/server.log" 2> /dev/null && break; sleep 0.1; done
grep -q "Server spawned" "$WORK/server.log" || { echo "FAIL the server did not start"; exit 1; }

echo "link: round trip $(python3 -c "print(int(2*$DELAY*1000))") ms + up to $(python3 -c "print(int(2*$JITTER*1000))") ms jitter, $(python3 -c "print($LOSS*100)")% loss each way; $JOINS joins"
pass=0
for i in $(seq 1 "$JOINS"); do
	d="$WORK/join$i"
	mkdir -p "$d/cl/data"
	echo "$LOSS $DELAY 0 $JITTER" > "$d/proxy.control"
	python3 "$ROOT/tests/net/udp_proxy.py" --listen 26001 --server 10.88.0.2:26000 --control "$d/proxy.control" --seed "$((527 + i))" ${REORDER:+--reorder} > "$d/proxy.log" 2>&1 &
	PRPID=$!
	sleep 0.2
	printf 'seta touch_profile_done 1\nseta _cl_name "Hp%d"\n' "$i" > "$d/cl/data/autoexec.cfg"
	t0=$(date +%s.%N)
	"$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d/cl" +vid_fullscreen 0 +vid_width 480 +vid_height 300 \
		+set _touch_assets_ready 1 ${NET_TRACE:++set touch_net_trace $NET_TRACE} ${CL_ARGS:-} +connect 127.0.0.1:26001 > "$d/client.log" 2>&1 &
	CLPID=$!
	ingame=0 changed=0
	for _ in $(seq 1 $((JOIN_TIMEOUT * 4))); do
		# the match ends and the map changes while this join downloads
		if [ "$i" = "$MAPCHANGE_JOIN" ] && [ "$changed" = 0 ] && grep -aq "Downloading new CSQC" "$d/client.log"; then
			sleep 3; echo "changelevel $MAP" > "$WORK/sv/data/next.cfg"; sleep 1; : > "$WORK/sv/data/next.cfg"; changed=1
			echo "note join $i: the map changed during its csprogs download"
		fi
		grep -aq "^Hp$i connected" "$WORK/server.log" && { ingame=1; break; }
		kill -0 "$CLPID" 2> /dev/null || break
		sleep 0.25
	done
	secs=$(python3 -c "import time; print(round(time.time() - $t0, 1))")
	sleep 1
	kill "$CLPID" 2> /dev/null; wait "$CLPID" 2> /dev/null
	kill "$PRPID" 2> /dev/null; wait "$PRPID" 2> /dev/null
	why=""
	[ "$ingame" = 1 ] || why="not in the game after ${JOIN_TIMEOUT} s ($(grep -a 'Connect:\|disconnect\|Disconnect' "$d/client.log" | grep -v '^execing' | tail -1))"
	grep -aq "joining .* again" "$d/client.log" && why="$why; reconnected: $(grep -a 'joining .* again' "$d/client.log" | head -1)"
	grep -aq "Connect: failed" "$d/client.log" && why="$why; $(grep -a 'Connect: failed' "$d/client.log" | head -1)"
	est=$(grep -ac "Connection accepted\|Connection established" "$d/client.log")
	[ "$est" -le 1 ] || why="$why; $est connections"
	note=""
	grep -aq "server is busy\|answers but ignores the connect" "$d/client.log" && note=" (waited out a connect flood block)"
	if [ -z "$why" ]; then
		pass=$((pass + 1)); echo "ok   join $i: in the game in $secs s$note"
	else
		echo "FAIL join $i: ${why#; }$note"
	fi
done
kill "$SVPID" 2> /dev/null; wait "$SVPID" 2> /dev/null
echo "first-attempt joins: $pass/$JOINS"
[ "$pass" = "$JOINS" ] && echo "all high-ping joins passed" || { echo "some high-ping joins FAILED"; exit 1; }
