#!/bin/bash
# Leaving the game and coming back (docs/NETWORK.md, "Staying connected"):
# a dedicated server in one network namespace, a client in another, joined by
# a veth cable (unshare -rn). Android freezes or suspends an app it does not
# keep alive; here the client process is stopped (SIGSTOP) and continued, the
# worst case of that. Cases:
#   freeze5, freeze60     stopped 5 s / 60 s in a match: still connected, no reconnect
#   freeze-signon         stopped 5 s during the signon: the join completes
#   timeout               stopped past the server's net_messagetimeout (40 s
#                         here): the server drops it; on return the client
#                         notices within seconds and joins again by itself
#   newaddress            the client's address changes in a match (Wi-Fi to
#                         mobile data) for good: once the server has gone quiet
#                         it gives the connection up and joins again by itself
#   addrflap              the address changes for 2.5 s and comes back: the
#                         same connection plays on, no reconnect
#   freeze-svhitch        stopped 8 s, then the server hitches 4 s: no reconnect
#   stall-signon,         one 40 s frame on the main thread (touch_net_stall: a
#   stall-match           slow tablet's CSQC_Init or shader compile) during the
#                         join / in a match: the server keeps the player, no
#                         reconnect (KEEP=0: net_keepalive_thread 0, must fail)
#   slow-join             the whole join at SLOW_RUN/(SLOW_RUN+SLOW_STOP) of the
#                         client's speed (tests/net/throttle.py): no drop
#   away                  touch_away 1 (Android's background): spectator after
#                         net_away_spectate (5 s here), back into the game on return
#   loss-freeze60         freeze60 with 2% loss
#   proxy-stall           freeze the client and interrupt its packets without tc
#   download              an HTTP pack download (2 MB/s cap) stopped for 10 s
#                         midway: it completes and the file is intact
#   reconnect-away        frozen past the server's timeout, joined again by
#                         itself, then away: spectator, then a clean leave
#   svfreeze-away         the same after the *server* froze 36 s (the client
#                         rejoins a slot the server still holds)
#   away-freeze           away first, then frozen past the timeout
#   mapchange-away        away across a map change
#   manual-away           away, then the player's own connect again
#   connect-again         connect to the same server while in the game (the
#                         menu's join on the server you are on): back in the
#                         game within seconds, no hang until a timeout
#   svdrop                the server drops a player who has played a while (kick, no
#                         reason): the client joins again once, a second drop within
#                         2 minutes is left alone; NETDROP lines say why
#   restart-signon        the client starts its connection over during the
#                         signon and its disconnect is lost: the server starts
#                         that connection over too and the join completes
#
#   BASEDIR=<Godnotic basedir> scripts/test-net-away.sh [CASE...]
# ENGINE, SERVER (default build/bin/xonotic, engine/darkplaces/darkplaces-dedicated), LIBS, OUT;
# NET_TRACE=1|2 logs the packets of a join (2: all) on both sides (touch_net_trace).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
SERVER="${SERVER:-$ROOT/engine/darkplaces/darkplaces-dedicated}"
BASEDIR="${BASEDIR:?set BASEDIR}"
MAP="${MAP:-stormkeep}"

if [ -z "${NET_TEST_INSIDE:-}" ]; then
	unshare -rn true 2> /dev/null || { echo "SKIP: no unprivileged network namespaces"; exit 77; }
	exec unshare -rn env NET_TEST_INSIDE=1 bash "$0" "$@"
fi
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-away.XXXXXX")"
cleanup() { jobs -p | xargs -r kill -CONT 2> /dev/null; jobs -p | xargs -r kill 2> /dev/null; [ -n "${OUT:-}" ] && mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; rm -rf "$WORK"; }
trap cleanup EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

ip link set lo up
unshare -n sleep 100000 & NSPID=$!
sleep 0.3
ip link add cl0 type veth peer name sv0
ip link set sv0 netns "$NSPID"
ip addr add 10.88.0.1/24 dev cl0
ip link set cl0 up
nsenter -t "$NSPID" -n sh -c 'ip link set lo up; ip addr add 10.88.0.2/24 dev sv0; ip link set sv0 up'
insv() { exec nsenter -t "$NSPID" -n "$@"; }

# start NAME "client cfg lines" "server args" -> CLPID, SVPID, logs in $WORK/NAME
start() {
	local name=$1 ccfg=$2 sargs=$3
	mkdir -p "$WORK/$name/cl/data" "$WORK/$name/sv/data"
	# poll.cfg runs every half second: the test writes commands into it
	printf '%b\nalias _ap "exec poll.cfg; defer 0.5 _ap"\n_ap\n' "$ccfg" > "$WORK/$name/cl/data/away.cfg"
	: > "$WORK/$name/cl/data/poll.cfg"
	printf 'alias _sp "exec next.cfg; defer 0.5 _sp"\n_sp\n' > "$WORK/$name/sv/data/svpoll.cfg"
	: > "$WORK/$name/sv/data/next.cfg"
	printf 'seta touch_profile_done 1\nseta _cl_name "Bench"\n' > "$WORK/$name/cl/data/autoexec.cfg"
	# shellcheck disable=SC2086
	insv "$SERVER" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$WORK/$name/sv" +set port 26000 +set sv_public 0 \
		+set bot_number 2 +set minplayers 0 ${NET_TRACE:++set touch_net_trace $NET_TRACE} $sargs +map "$MAP" +exec svpoll.cfg > "$WORK/$name/server.log" 2>&1 & SVPID=$!
	for _ in $(seq 1 300); do grep -q "Server spawned" "$WORK/$name/server.log" 2> /dev/null && break; sleep 0.1; done
	"$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$WORK/$name/cl" +vid_fullscreen 0 +vid_width 480 +vid_height 300 \
		+set _touch_assets_ready 1 ${NET_TRACE:++set touch_net_trace $NET_TRACE} +exec away.cfg > "$WORK/$name/client.log" 2>&1 & CLPID=$!
}
finish() { # the client quits by itself; the server goes with it
	wait "$CLPID" 2> /dev/null
	kill "$SVPID" 2> /dev/null
	wait "$SVPID" 2> /dev/null
	sleep 0.5
}
wait_for() { # file pattern seconds
	local i
	for i in $(seq 1 $(($3 * 10))); do grep -q "$2" "$1" 2> /dev/null && return 0; sleep 0.1; done
	return 1
}
count() { grep -c "$2" "$1" 2> /dev/null || true; }
# a command for the running client (idempotent ones: it runs every 0.5 s until cleared)
say() { echo "$2" > "$WORK/$1/cl/data/poll.cfg"; sleep 1.2; : > "$WORK/$1/cl/data/poll.cfg"; }
# a command that must run exactly once (connect, changelevel) through a polled
# cfg: first an alias that disarms itself when run, then its call
ONCE=0
once() { # cfg-file command
	ONCE=$((ONCE + 1))
	printf 'alias _once%d "%s; alias _once%d echo"\n' "$ONCE" "$2" "$ONCE" > "$1"; sleep 1.2
	echo "_once$ONCE" > "$1"; sleep 1.2; : > "$1"
}
svpid() { pgrep -f -- "-userdir $WORK/$1/sv" | head -1; }
# the server log after the client's last (re)join
since_join() { awk '/^Bench connected/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' "$WORK/$1/server.log"; }
wait_since() { # name pattern seconds: in the server log after the last join
	local i
	for i in $(seq 1 $(($3 * 2))); do since_join "$1" | grep -q "$2" && return 0; sleep 0.5; done
	return 1
}
# stop the client (or the server) past the server's timeout, then wait until
# the client has joined again by itself and is in the game
freeze_and_rejoin() { # name client|server seconds
	local name=$1 who=$2 secs=$3 pid
	pid=$CLPID; [ "$who" = server ] && pid=$(svpid "$name")
	kill -STOP "$pid"; sleep "$secs"; kill -CONT "$pid"
	for _ in $(seq 1 120); do [ "$(count "$WORK/$name/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.5; done
	# back in the slot it had (a server keeps a player who comes back from
	# the same address in the game, so there may be no "is now playing")
	[ "$(count "$WORK/$name/server.log" "^Bench connected")" -ge 2 ] || return 1
	say "$name" "togglemenu 0; cmd join"
	sleep 4
}
# Bench as the server sees it: "spectator" (frags -666: an observer or a
# spectator), "player", or "gone"; the server's own status, not the log (a
# server that kept the slot prints no new "is now playing" after a rejoin)
sv_state() { # name
	local f
	echo "sv_status_show_qcstatus 0; status" > "$WORK/$1/sv/data/next.cfg"; sleep 1.2; : > "$WORK/$1/sv/data/next.cfg"
	f=$(grep -a "^#[0-9]* *Bench " "$WORK/$1/server.log" | tail -1 | awk '{print $3}')
	case $f in -666) echo spectator ;; "") echo gone ;; *) echo player ;; esac
}
wait_sv_state() { # name state seconds
	local t=$(($(date +%s) + $3))
	while [ "$(date +%s)" -lt "$t" ]; do [ "$(sv_state "$1")" = "$2" ] && return 0; done
	return 1
}
# away after a rejoin (the app has been in the background for more than
# net_away_spectate): not a player (a fresh slot is an observer, a slot the
# server kept plays on and has to be moved), also when something puts it
# back in the game (an auto-join: here the test's own join), then the clean
# disconnect at net_away_limit that frees the slot
check_away() { # name label
	local name=$1 label=$2
	wait_since "$name" "^Bench connected" 1 || { bad "$name: $label: no rejoin in the server log"; return; }
	if wait_sv_state "$name" spectator 20; then
		ok "$name: $label: not a player after net_away_spectate ($(since_join "$name" | grep -aq "^Bench.*is now.*spectating" && echo moved to the spectators || echo an observer since the rejoin))"
	else
		bad "$name: $label: not moved to the spectators"
	fi
	say "$name" "cmd join"
	if ! wait_since "$name" "^Bench.*is now playing" 10; then
		bad "$name: $label: the test's join did not reach the server"
	elif wait_sv_state "$name" spectator 15; then
		ok "$name: $label: put back in the game while away, moved to the spectators again"
	else
		bad "$name: $label: put back in the game while away, stays a player"
	fi
	if wait_for "$WORK/$name/client.log" "Left .* after .* in the background" 90 && wait_since "$name" 'Client "Bench" dropped (Disconnect by user)' 5; then
		ok "$name: $label: left at net_away_limit, the server freed the slot, the client says why ($(grep -o 'Left .* background' "$WORK/$name/client.log" | head -1))"
	else
		bad "$name: $label: no clean disconnect at net_away_limit ($(grep -a 'Left .* background' "$WORK/$name/client.log" | head -1); server: $(grep 'Bench" dropped' "$WORK/$name/server.log" | tr '\n' ' '))"
	fi
}
AWAYCFG='set net_away_spectate 5\nset net_away_limit 30'
# in the game, and the remaining joins of $JOIN called off: they would fire
# later, in the middle of a case (a stray join after a rejoin while away)
playing() {
	wait_for "$WORK/$1/server.log" "Bench.*is now playing" 60 || { bad "$1: the client never got into the game"; return 1; }
	say "$1" 'alias _j "set _j_off 1"'
}
# join the game a few times over: on a loaded machine the signon may not be
# through by the first one (a join while playing does nothing)
JOIN='alias _j "togglemenu 0; cmd join"\nconnect 10.88.0.2:26000\ndefer 10 _j\ndefer 20 _j\ndefer 28 _j'

freeze_case() { # name seconds when(match|signon) proxy-loss
	local name=$1 secs=$2 when=$3
	local join="$JOIN" proxy_pid=
	if [ -n "${4:-}" ]; then
		mkdir -p "$WORK/$name"
		echo "0.02 0.02 0" > "$WORK/$name/proxy.control"
		python3 "$ROOT/tests/net/udp_proxy.py" --listen 26001 --server 10.88.0.2:26000 --control "$WORK/$name/proxy.control" > "$WORK/$name/proxy.log" 2>&1 & proxy_pid=$!
		join=${join//10.88.0.2:26000/127.0.0.1:26001}
	fi
	start "$name" "$join\ndefer $((secs + 90)) quit" ""
	if [ "$when" = signon ]; then
		wait_for "$WORK/$name/client.log" "Connection established" 30
	elif ! wait_for "$WORK/$name/server.log" "Bench.*is now playing" 60; then
		bad "$name: the client never got into the game (see client and server logs)"; kill "$CLPID" 2>/dev/null; finish; return
	fi
	sleep 1
	kill -STOP "$CLPID"; sleep "$secs"; kill -CONT "$CLPID"
	# Give the connection time to recover; do not wait for a deferred quit,
	# whose game-time clock may stop while the process is frozen.
	sleep 12
	kill "$CLPID" 2>/dev/null
	finish
	[ -z "$proxy_pid" ] || { kill "$proxy_pid" 2>/dev/null; wait "$proxy_pid" 2>/dev/null; }
	local est again
	est=$(count "$WORK/$name/client.log" "Connection established")
	again=$(count "$WORK/$name/client.log" "joining .* again")
	if [ "$est" = 1 ] && [ "$again" = 0 ] && ! grep 'Client "Bench" dropped' "$WORK/$name/server.log" | grep -vq "Disconnect by user"; then
		ok "$name: stopped $secs s ($when), still connected, no reconnect"
	elif grep -q 'Bench" dropped (Timed out)' "$WORK/$name/server.log" && [ "$again" -ge 1 ] && [ "$(count "$WORK/$name/server.log" "^Bench connected")" -ge 2 ]; then
		# longer than the server's net_messagetimeout (Xonotic: 30 s)
		ok "$name: stopped $secs s, the server timed it out, it joined again by itself"
	else
		bad "$name: $est connections, $again reconnects; server: $(grep 'Bench" dropped' "$WORK/$name/server.log" | tr '\n' ' ')"
	fi
	[ "$when" = signon ] && { [ "$(count "$WORK/$name/server.log" "^Bench connected")" -ge 1 ] && ok "$name: the join completed" || bad "$name: the join did not complete"; }
}

# A join on a slow device (the owner's tablet showed "Reconnecting ... attempt
# 1 of 5" over its precache): the client runs at run/(run+stop) of its speed
# from the moment it has a connection until it is in the game. The server
# drops a client that has not begun after net_connecttimeout (15 s) of
# silence, so the join must keep it hearing from us however slowly it loads,
# and must never start over.
slow_join_case() { # name run_ms stop_ms
	local name=$1 run=$2 stop=$3 tpid
	start "$name" "connect 10.88.0.2:26000\ndefer 400 quit" "${SLOW_SV:-}"
	wait_for "$WORK/$name/client.log" "Connection accepted\|Connection established" 30 || { bad "$name: no connection"; kill "$CLPID" 2>/dev/null; finish; return; }
	python3 "$ROOT/tests/net/throttle.py" "$CLPID" --run "$run" --stop "$stop" --seconds 300 --until-file "$WORK/$name/in" & tpid=$!
	local t0; t0=$(date +%s)
	if wait_for "$WORK/$name/server.log" "^Bench.*is now \(playing\|spectating\|observing\)\|Bench.* entered the game\|^Bench connected" 300; then :; fi
	: > "$WORK/$name/in"; wait "$tpid" 2>/dev/null
	sleep 3
	local secs=$(( $(date +%s) - t0 )) svlog; svlog=$(grep -av NETTRACE "$WORK/$name/server.log")
	kill "$CLPID" 2>/dev/null; finish
	if grep -aq 'Bench.*\(timed out\|dropped\)' <<< "$svlog"; then
		bad "$name: the server dropped the slow joiner ($(grep -a 'Bench.*\(timed out\|dropped\)' <<< "$svlog" | head -1))"
	else ok "$name: the server kept the slow joiner for its whole ${secs} s join"; fi
	if grep -aq 'joining .* again\|Reconnecting' "$WORK/$name/client.log"; then
		bad "$name: the client started the join over ($(grep -a 'joining .* again' "$WORK/$name/client.log" | head -1))"
	else ok "$name: no reconnect during the slow join"; fi
	[ "$(count "$WORK/$name/server.log" "^Bench connected")" = 1 ] && ok "$name: in the game on the first connection" || bad "$name: $(count "$WORK/$name/server.log" "^Bench connected") connections to get in"
}

# One frame that takes 40 s on the main thread (touch_net_stall: CSQC_Init or
# a driver's shader compile on a slow tablet), with nothing else stopped: the
# server (net_connecttimeout / net_messagetimeout 30) must keep hearing from
# us. KEEP=0 runs it with net_keepalive_thread 0, which must fail.
stall_case() { # name signon|match
	local name=$1 when=$2 keep=${KEEP:-1} stalled
	if [ "$when" = signon ]; then
		start "$name" "set net_keepalive_thread $keep\ntouch_net_stall 40 join\nconnect 10.88.0.2:26000\ndefer 150 quit" ""
	else
		start "$name" "set net_keepalive_thread $keep\n$JOIN\ndefer 150 quit" ""
		playing "$name" || { kill "$CLPID" 2>/dev/null; finish; return; }
		once "$WORK/$name/cl/data/poll.cfg" "touch_net_stall 40"
	fi
	wait_for "$WORK/$name/client.log" "Stalling the main thread" 60 || { bad "$name: no stall"; kill "$CLPID" 2>/dev/null; finish; return; }
	stalled=$(grep -ao "Stalling the main thread .*" "$WORK/$name/client.log" | head -1)
	# the stall, then time for the client to hear it was dropped and join again
	sleep 70
	local svlog; svlog=$(grep -av NETTRACE "$WORK/$name/server.log")
	kill "$CLPID" 2>/dev/null; finish
	echo "note $name: $stalled (net_keepalive_thread $keep)"
	case "$when:$stalled" in signon:*"signon 0 of"*|signon:*"signon 4 of 4"*) bad "$name: the stall came after the signon (the test did not stall a join)" ;; esac
	if grep -aq 'Bench.*\(timed out\|dropped\)' <<< "$svlog"; then
		bad "$name: the server dropped the stalled client ($(grep -a 'Bench.*\(timed out\|dropped\)' <<< "$svlog" | head -1))"
	else ok "$name: the server kept the client through a 40 s frame"; fi
	if grep -aq 'joining .* again' "$WORK/$name/client.log"; then
		bad "$name: the client reconnected ($(grep -a 'joining .* again' "$WORK/$name/client.log" | head -1))"
	else ok "$name: no reconnect"; fi
	[ "$(count "$WORK/$name/client.log" "Connection established")" = 1 ] && [ "$(grep -ac "^Bench connected" <<< "$svlog")" = 1 ] && ok "$name: in the game on the first connection" || bad "$name: not in the game on the first connection"
}

run() {
	case $1 in
		freeze5) freeze_case freeze5 5 match ;;
		proxy-stall)
			mkdir -p "$WORK/proxy-stall"
			echo "0.02 0.02 0" > "$WORK/proxy-stall/proxy.control"
			python3 "$ROOT/tests/net/udp_proxy.py" --listen 26001 --server 10.88.0.2:26000 --control "$WORK/proxy-stall/proxy.control" > "$WORK/proxy-stall/proxy.log" 2>&1 & proxy_pid=$!
			proxy_join=${JOIN//10.88.0.2:26000/127.0.0.1:26001}
			start proxy-stall "$proxy_join\ndefer 90 quit" ""
			if playing proxy-stall; then
				echo "0.02 0.02 12" > "$WORK/proxy-stall/proxy.control"
				sleep 1
				kill -STOP "$CLPID"; sleep 6; kill -CONT "$CLPID"
				sleep 11
				[ "$(count "$WORK/proxy-stall/client.log" "Connection established")" = 1 ] && ! grep -q 'joining .* again' "$WORK/proxy-stall/client.log" && [ "$(sv_state proxy-stall)" = player ] && ok "proxy-stall: survived a freeze, 12 s interruption and 2% loss in the same slot" || bad "proxy-stall: connection or player slot lost"
			fi
			kill "$CLPID" 2>/dev/null; finish
			kill "$proxy_pid" 2>/dev/null; wait "$proxy_pid" 2>/dev/null ;;
		freeze60) freeze_case freeze60 60 match ;;
		loss-freeze60) freeze_case loss-freeze60 60 match "delay 20ms loss 2%" ;;
		freeze-signon) freeze_case freeze-signon 5 signon ;;
		stall-signon) stall_case stall-signon signon ;;
		stall-match) stall_case stall-match match ;;
		slow-join) slow_join_case slow-join "${SLOW_RUN:-40}" "${SLOW_STOP:-460}" ;;
		timeout)
			start timeout "$JOIN\ndefer 130 quit" "+set net_messagetimeout 40"
			wait_for "$WORK/timeout/server.log" "Bench.*is now playing" 60 || bad "timeout: the client never got into the game"; sleep 1
			kill -STOP "$CLPID"; sleep 50; kill -CONT "$CLPID"
			wait_for "$WORK/timeout/client.log" "joining .* again" 20 && t1=$(date +%s)
			wait_for "$WORK/timeout/server.log" "Bench.*is now playing.*\|^Bench connected" 0
			finish
			grep -q 'Bench" dropped (Timed out)' "$WORK/timeout/server.log" && ok "timeout: the server dropped the frozen client after net_messagetimeout" || bad "timeout: no server-side timeout"
			[ "$(count "$WORK/timeout/client.log" "Connection established")" -ge 2 ] && [ "$(count "$WORK/timeout/server.log" "^Bench connected")" -ge 2 ] \
				&& ok "timeout: back, the client noticed ($(grep -o 'Lost connection[^:]*' "$WORK/timeout/client.log" | head -1)) and joined again by itself" \
				|| bad "timeout: no automatic reconnect ($(grep -a 'Lost\|again\|Reconnect' "$WORK/timeout/client.log" | head -3 | tr '\n' ' '))" ;;
		newaddress) # a lasting change: the server cannot reach us, so join again soon
			start newaddress "$JOIN\ndefer 70 quit" ""
			wait_for "$WORK/newaddress/server.log" "Bench.*is now playing" 40; sleep 2
			ip addr add 10.88.0.9/24 dev cl0; ip addr del 10.88.0.1/24 dev cl0
			finish
			ip addr add 10.88.0.1/24 dev cl0 2> /dev/null; ip addr del 10.88.0.9/24 dev cl0 2> /dev/null
			grep -q "address changed (10.88.0.1 -> 10.88.0.9)" "$WORK/newaddress/client.log" && ok "newaddress: the client saw its address change" || bad "newaddress: change not seen"
			grep -q "Lost connection to server (this device.s address changed, nothing from it" "$WORK/newaddress/client.log" && ok "newaddress: gave up the old connection once the server went quiet" || bad "newaddress: did not give up the dead connection"
			[ "$(count "$WORK/newaddress/server.log" "^Bench connected")" -ge 2 ] && ok "newaddress: joined again from 10.88.0.9 by itself" || bad "newaddress: no reconnect" ;;
		freeze-svhitch) # frozen 8 s (well inside the server's 30 s), then the server hitches 4 s (a busy server, a mobile network gap): no reason to reconnect
			start freeze-svhitch "$JOIN\ndefer 80 quit" ""
			if playing freeze-svhitch; then
				sleep 1
				kill -STOP "$CLPID"; sleep 8; kill -CONT "$CLPID"
				sleep 0.5
				kill -STOP "$SVPID"; sleep 4; kill -CONT "$SVPID"
				sleep 8
				[ "$(sv_state freeze-svhitch)" = player ] && ok "freeze-svhitch: still a player" || bad "freeze-svhitch: lost the player slot"
			fi
			kill "$CLPID" 2>/dev/null; finish
			grep -aq 'joining .* again' "$WORK/freeze-svhitch/client.log" && bad "freeze-svhitch: reconnected ($(grep -a 'joining .* again' "$WORK/freeze-svhitch/client.log" | head -1))" || ok "freeze-svhitch: no reconnect"
			[ "$(count "$WORK/freeze-svhitch/server.log" "^Bench connected")" = 1 ] && ok "freeze-svhitch: one connection" || bad "freeze-svhitch: $(count "$WORK/freeze-svhitch/server.log" "^Bench connected") connections" ;;
		addrflap) # Wi-Fi drops for 2 s and comes back with the same address: the same connection plays on
			start addrflap "$JOIN\ndefer 60 quit" ""
			if playing addrflap; then
				sleep 2
				ip addr add 10.88.0.9/24 dev cl0; ip addr del 10.88.0.1/24 dev cl0
				sleep 2.5
				ip addr add 10.88.0.1/24 dev cl0; ip addr del 10.88.0.9/24 dev cl0
				sleep 8
				[ "$(sv_state addrflap)" = player ] && ok "addrflap: still a player after the flap" || bad "addrflap: lost the player slot"
			fi
			kill "$CLPID" 2>/dev/null; finish
			grep -aq 'joining .* again\|Lost connection' "$WORK/addrflap/client.log" && bad "addrflap: reconnected over a 2.5 s address flap ($(grep -a 'Lost connection' "$WORK/addrflap/client.log" | head -1))" || ok "addrflap: no reconnect over a 2.5 s address flap"
			[ "$(count "$WORK/addrflap/server.log" "^Bench connected")" = 1 ] && ok "addrflap: one connection" || bad "addrflap: $(count "$WORK/addrflap/server.log" "^Bench connected") connections" ;;
		away)
			start away "$JOIN\nset net_away_spectate 5\ndefer 30 \"touch_away 1\"\ndefer 45 \"touch_away 0\"\ndefer 60 quit" ""
			finish
			grep -q "going to the spectators" "$WORK/away/client.log" && grep -q "Bench.*is now.*spectating" "$WORK/away/server.log" \
				&& ok "away: spectator after 5 s in the background" || bad "away: not moved to the spectators"
			grep -q "Back: joining the game again" "$WORK/away/client.log" && [ "$(count "$WORK/away/server.log" "Bench.*is now playing")" -ge 2 ] \
				&& ok "away: back into the game on return" || bad "away: did not rejoin the game" ;;
		download)
			mkdir -p "$WORK/www"
			python3 - "$WORK/www/zzawaypack.pk3" <<'EOF'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_STORED) as z:
    for i in range(6):
        z.writestr("away/blob%d.bin" % i, os.urandom(4 << 20))
EOF
			# not through insv: a backgrounded function is a subshell, and
			# killing it would leave the server running
			nsenter -t "$NSPID" -n python3 "$ROOT/tests/net/range_http.py" "$WORK/www" 8124 2048 2> "$WORK/http.log" & HP=$!
			sleep 0.5
			start download "curl --pak http://10.88.0.2:8124/zzawaypack.pk3\nset developer 1\ndefer 60 quit" ""
			sleep 5
			kill -STOP "$CLPID"; sleep 10; kill -CONT "$CLPID"
			finish; kill $HP 2> /dev/null
			cmp -s "$WORK/www/zzawaypack.pk3" "$WORK/download/cl/data/dlcache/zzawaypack.pk3" && grep -q "Download of .*: OK" "$WORK/download/client.log" \
				&& ok "download: stopped 10 s midway, completed, identical" || bad "download: $(grep -a 'Download of' "$WORK/download/client.log" | head -1)" ;;
		reconnect-away) # frozen past the server's timeout, joined again by itself, then away
			start reconnect-away "$JOIN\n$AWAYCFG\ndefer 240 quit" ""
			playing reconnect-away || { finish; return; }
			freeze_and_rejoin reconnect-away client 40 && ok "reconnect-away: rejoined by itself after a 40 s freeze" || bad "reconnect-away: did not get back into the game"
			say reconnect-away "touch_away 1"
			check_away reconnect-away "away after the reconnect"
			finish ;;
		svfreeze-away) # the server froze 36 s, the client timed out and joined again, then away
			start svfreeze-away "$JOIN\n$AWAYCFG\ndefer 240 quit" ""
			playing svfreeze-away || { finish; return; }
			freeze_and_rejoin svfreeze-away server 36 && ok "svfreeze-away: rejoined by itself after the server froze 36 s" || bad "svfreeze-away: did not get back into the game"
			say svfreeze-away "touch_away 1"
			check_away svfreeze-away "away after the reconnect"
			finish ;;
		away-freeze) # away first, then frozen past the timeout: joined again while away
			# (net_away_limit 75: the loss has to be noticed within seconds of
			# the freeze; judged by the 30 s silence limit the away limit came
			# first and its clean disconnect went to a server that had dropped it)
			start away-freeze "$JOIN\nset net_away_spectate 5\nset net_away_limit 75\ndefer 240 quit" ""
			playing away-freeze || { finish; return; }
			say away-freeze "touch_away 1"
			wait_since away-freeze "Bench.*is now.*spectating" 25 && ok "away-freeze: spectator" || bad "away-freeze: not a spectator before the freeze"
			kill -STOP "$CLPID"; sleep 40; kill -CONT "$CLPID"
			for _ in $(seq 1 120); do [ "$(count "$WORK/away-freeze/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.5; done
			check_away away-freeze "reconnected while away"
			finish ;;
		mapchange-away) # away across a map change
			start mapchange-away "$JOIN\nset net_away_spectate 5\nset net_away_limit 45\ndefer 240 quit" ""
			playing mapchange-away || { finish; return; }
			say mapchange-away "touch_away 1"
			wait_since mapchange-away "Bench.*is now.*spectating" 25 && ok "mapchange-away: spectator" || bad "mapchange-away: not a spectator"
			once "$WORK/mapchange-away/sv/data/next.cfg" "changelevel $MAP"
			for _ in $(seq 1 60); do [ "$(count "$WORK/mapchange-away/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.5; done
			check_away mapchange-away "after a map change"
			finish ;;
		manual-away) # away, then the player's own reconnect (connect again)
			start manual-away "$JOIN\nset net_away_spectate 5\nset net_away_limit 40\ndefer 240 quit" ""
			playing manual-away || { finish; return; }
			say manual-away "touch_away 1"
			wait_since manual-away "Bench.*is now.*spectating" 25 || bad "manual-away: not a spectator"
			once "$WORK/manual-away/cl/data/poll.cfg" "connect 10.88.0.2:26000"
			for _ in $(seq 1 60); do [ "$(count "$WORK/manual-away/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.5; done
			check_away manual-away "after a manual reconnect"
			finish ;;
		connect-again) # the player joins the server they are on again
			start connect-again "$JOIN\ndefer 120 quit" ""
			playing connect-again || { finish; return; }
			sleep 2
			t0=$(date +%s.%N)
			echo "connect 10.88.0.2:26000" > "$WORK/connect-again/cl/data/poll.cfg"; sleep 0.6; : > "$WORK/connect-again/cl/data/poll.cfg"
			for _ in $(seq 1 60); do [ "$(count "$WORK/connect-again/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.25; done
			t1=$(date +%s.%N)
			sleep 3
			kill "$CLPID" 2> /dev/null
			finish
			if [ "$(count "$WORK/connect-again/server.log" "^Bench connected")" -ge 2 ] && awk -v a="$t0" -v b="$t1" 'BEGIN{exit !(b - a < 10)}'; then
				ok "connect-again: back in the game $(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b - a}') s after the connect"
			else
				bad "connect-again: not back within 10 s ($(grep 'Bench" dropped' "$WORK/connect-again/server.log" | tr '\n' ' '))"
			fi
			grep -aq "Connection timed out\|Lost connection" "$WORK/connect-again/client.log" && bad "connect-again: the new connection hung until a timeout" || ok "connect-again: no timeout" ;;
		restart-signon) # connect again during the signon while nothing reaches the server (the disconnect is lost)
			# _rs connects again once, however often poll.cfg runs (the
			# client may be stuck in a long map-loading frame meanwhile)
			mkdir -p "$WORK/restart-signon"
			echo "0 0 0" > "$WORK/restart-signon/proxy.control"
			python3 "$ROOT/tests/net/udp_proxy.py" --listen 26001 --server 10.88.0.2:26000 --control "$WORK/restart-signon/proxy.control" > "$WORK/restart-signon/proxy.log" 2>&1 & proxy_pid=$!
			start restart-signon "alias _rs \"echo RESTART; connect 127.0.0.1:26001; alias _rs echo\"\nconnect 127.0.0.1:26001\ndefer 90 quit" ""
			wait_for "$WORK/restart-signon/client.log" "Connection established" 30 || { bad "restart-signon: no connection"; finish; return; }
			sleep 0.5 # the server has heard from this connection (acks, keepalives)
			echo "1 0 0" > "$WORK/restart-signon/proxy.control"
			sleep 0.2
			c0=$(count "$WORK/restart-signon/server.log" "^Bench connected")
			echo "_rs" > "$WORK/restart-signon/cl/data/poll.cfg"
			wait_for "$WORK/restart-signon/client.log" "^RESTART" 30 || bad "restart-signon: the client did not run the restart"
			sleep 1.5
			echo "0 0 0" > "$WORK/restart-signon/proxy.control"
			[ "$c0" = 0 ] || echo "note restart-signon: the first signon had reached spawn before the restart"
			if wait_for "$WORK/restart-signon/server.log" "joined again during its signon" 15; then ok "restart-signon: the server saw the restart and started the connection over"; else bad "restart-signon: the server answered with a duplicate accept on the old connection"; fi
			for _ in $(seq 1 60); do [ "$(count "$WORK/restart-signon/server.log" "^Bench connected")" -gt "$c0" ] && break; sleep 0.5; done
			if [ "$(count "$WORK/restart-signon/server.log" "^Bench connected")" -gt "$c0" ]; then ok "restart-signon: the join completed after the restart"; else bad "restart-signon: the join hangs after the restart ($(grep 'Bench" dropped' "$WORK/restart-signon/server.log" | tr '\n' ' '))"; fi
			kill "$CLPID" 2> /dev/null
			finish
			kill "$proxy_pid" 2>/dev/null; wait "$proxy_pid" 2>/dev/null ;;
		svdrop) # the server drops a player who has been playing for a while and says nothing: joined again once
			start svdrop "$JOIN\nset net_reconnect_server_min 4\ndefer 150 quit" ""
			playing svdrop || { finish; return; }
			sleep 6
			once "$WORK/svdrop/sv/data/next.cfg" "kick Bench"
			for _ in $(seq 1 60); do [ "$(count "$WORK/svdrop/server.log" "^Bench connected")" -ge 2 ] && break; sleep 0.5; done
			if [ "$(count "$WORK/svdrop/server.log" "^Bench connected")" -ge 2 ]; then ok "svdrop: joined again by itself after the server dropped the player"; else bad "svdrop: no rejoin after the server's drop"; fi
			grep -aq "^NETDROP: \"Server disconnected\"" "$WORK/svdrop/client.log" && ok "svdrop: the drop was logged with its cause (NETDROP)" || bad "svdrop: no NETDROP line"
			sleep 7
			# a second drop within 2 minutes is not joined again: it stays a disconnect, with the reason shown
			once "$WORK/svdrop/sv/data/next.cfg" "kick Bench"
			sleep 8
			if [ "$(count "$WORK/svdrop/server.log" "^Bench connected")" -eq 2 ]; then ok "svdrop: the second drop in 2 minutes was left alone"; else bad "svdrop: joined a second time"; fi
			grep -aq "Disconnected by" "$WORK/svdrop/client.log" || grep -aq "_net_reconnect_state" "$WORK/svdrop/client.log" || echo "note svdrop: reason line is in the UI state only"
			kill "$CLPID" 2> /dev/null
			finish ;;
		*) bad "unknown case $1" ;;
	esac
}

[ $# -gt 0 ] || set -- freeze5 proxy-stall freeze-signon stall-signon stall-match freeze-svhitch addrflap away newaddress timeout download freeze60 loss-freeze60 reconnect-away svfreeze-away away-freeze mapchange-away manual-away connect-again restart-signon
for c in "$@"; do run "$c"; done
[ $fail = 0 ] && echo "all away tests passed" || echo "some away tests FAILED"
exit $fail
