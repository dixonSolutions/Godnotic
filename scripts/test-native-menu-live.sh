#!/bin/bash
# The menu a native front end shows over a running match, offscreen: a
# Godnotic client with a native front end attached through the front-end
# bridge (`fe attach`, a heartbeat, a UI-bridge hello, events as `fe ui`),
# connected to a dedicated server that runs STOCK Xonotic game code (what
# every public server is). Nothing is drawn by Android here: the test reads
# what the engine publishes (the app topic's state, the bridge's modal list).
#
#   uid2name  The game code asks "use your name in player statistics?" while
#             cl_allow_uid2name is -1 and asks again on every frame the menu
#             is not on screen. With a native front end that used to show the
#             bare pause page, and Resume closed it only for the menu to come
#             back on the next frame (owner's report, 2026-11-14: "pressing
#             resume on a live match flashes the game but leaves the pause
#             screen"). Now: the question is the bridge's own dialog
#             (modal "uid2name"); No sets the cvar to 0 and the match is back;
#             then MENU > Resume leaves the menu down. Back answers No too.
#
#   joinmid   A player who joins a match already under way, on a server that
#             runs Godnotic's (newer) game code. That code asks for the game's
#             Welcome dialog on connect, a stock dialog a native front end
#             cannot draw; the menu took it as the join question answered, so
#             the player got a bare pause page and no join dialog until the
#             next map (tablet check, release round 2). Now: the join popup
#             opens (modal "join"), Spectate is respected (it closes and does
#             not come back), and the pause page names the game: the address
#             joined, "LAN game", no action on the card.
#
#   joinname  Every join screen names the server: "Joining <name> (<address>)".
#             The server's hostname has colour codes and is long; a relay
#             (tests/native-menu/udp_delay.py) holds its challenge back 4 s and
#             its info answer 2 s, as a far server would. Joined from the server
#             list, the loading card names it from the first frame; joined by a
#             typed address, it shows the address, then the name once the server
#             says it. The connect topic's label, and the pause page's card,
#             follow. Colour codes are gone and the name ends in an ellipsis.
#
#   joinui    Joining from spectating on a stock server, Deathmatch and CTF, at
#             a tablet's size, with scripted fingers through the real SDL event
#             path (tests/touch-controls/finj.c). The join dialog (modal
#             "join") opens by itself at connect: Xonotic's team selection in
#             our style (each team's colour and player count, Auto-select,
#             Spectate; Join and Spectate without teams), not dismissable,
#             taking no touch for its first moments. Taps beside it leave it
#             up. A team (CTF) or Join (DM) puts the player in. "Swipe to join"
#             is on the HUD while spectating and gone while playing; in CTF a
#             swipe on it opens the dialog again, in DM a tap on it joins.
#
#   BASEDIR=<Godnotic basedir> STOCKBASE=<basedir with upstream Xonotic data only> \
#       scripts/test-native-menu-live.sh [uid2name] [joinmid] [joinname] [joinui]
#
# Environment: ENGINE (default build/bin/xonotic; it also runs the server),
# LIBS (extra LD_LIBRARY_PATH), OUT (keep logs there), CPUS (a taskset list).
# Exit 77: skipped (no engine or data).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
BASEDIR="${BASEDIR:-}"
STOCKBASE="${STOCKBASE:-}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] && [ -d "$STOCKBASE/data" ] || { echo "SKIP: set ENGINE, BASEDIR and STOCKBASE"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
RUN=()
[ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-nativemenu.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	if [ -n "${OUT:-}" ]; then mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; fi
	rm -rf "$WORK"
}
trap cleanup EXIT
fail=0

# one `fe ui <message>` line: the message percent-encoded, as fe/FeArgs.kt sends it
ui() { python3 -c 'import json,sys,urllib.parse; print("fe ui " + urllib.parse.quote(json.dumps(json.loads(sys.argv[1]), separators=(",", ":")), safe=""))' "$1"; }

server() { # name port [basedir: default the stock game code] [hostname]
	local d="$WORK/$1"
	mkdir -p "$d"
	"${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "${3:-$STOCKBASE}" -userdir "$d" +set port "$2" +set sv_public 0 \
		+set bot_number 2 +set skill 1 +set g_warmup 0 ${4:++set} ${4:+hostname} ${4:+"$4"} +map stormkeep > "$d/server.log" 2>&1 &
	local n; for n in $(seq 1 600); do grep -q "Server spawned" "$d/server.log" 2> /dev/null && break; sleep 0.1; done
	sleep 2
}

client() { # name port answer(no|back)
	local d="$WORK/$1" answer
	mkdir -p "$d/data"
	if [ "$3" = back ]; then answer=$(ui '{"uib":1,"t":"ev","id":3,"ev":"back"}')
	else answer=$(ui '{"uib":1,"t":"ev","id":3,"ev":"action","node":"uid2name/no","action":"uid2name.no"}'); fi
	{
		echo 'set cl_welcome 0; set touch_join_dialog_auto 0; set developer 1; set fe_echo 1'
		echo 'alias xbeat "fe beat; defer 1 xbeat"'
		echo 'alias xshow "cl_allow_uid2name"'   # a cvar's name alone prints its value when it runs
		ui '{"uib":1,"t":"hello","id":1,"schema":"1.0","renderer":{"name":"test"},"caps":{"widthClass":"expanded"}}' | sed 's/^/alias xhello "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":4,"ev":"action","node":"pause/actions/resume","action":"resume"}' | sed 's/^/alias xresume "/; s/$/"/'
		echo "alias xanswer \"$answer\""
		echo "connect 127.0.0.1:$2"
		echo 'defer 3 "fe attach 1; xbeat; xhello"'
		# the stock menu initialises in the frames the native UI is away (the device
		# does this on every return from the background): that is when it began to
		# reopen itself for the question
		echo 'defer 28 "fe detach test; defer 0.5 \"fe attach 1; xhello\""'
		echo 'defer 31 "echo MARK asked; xshow; uib_dump"'
		echo 'defer 32 "echo MARK answer; xanswer"'
		echo 'defer 33.5 "echo MARK answered; xshow; uib_dump"'
		echo 'defer 34 "echo MARK pause; menu_showgamemenudialog"'
		echo 'defer 35 "echo MARK resume; xresume"'
		echo 'defer 37 "echo MARK end; quit"'
	} > "$d/data/tour.cfg"
	timeout -k 5 70 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
		+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
		+set _touch_assets_ready 1 +set touch_setup_done 1 +set touch_profile_done 1 +set touch_native_frontend 1 \
		+exec tour.cfg > "$d/client.log" 2>&1
}

case_uid2name() {
	local p answer
	for answer in no back; do
		p=$((26700 + RANDOM % 250))
		server "srv_$answer" $p
		client "uid2name_$answer" $p $answer
		kill %1 2> /dev/null; wait 2> /dev/null
		python3 "$ROOT/tests/native-menu/check_uid2name.py" "$WORK/uid2name_$answer/client.log" "$answer" || fail=1
	done
}

# A match under way on a Godnotic-code server. The client first plays it with the
# QuakeC menu (the menu draws while connected, as it does on a device whenever the
# app comes back from the background), attaches the native front end, leaves and
# joins again through Multiplayer's address field. The menu saw "connected" last.
case_joinmid() {
	local p d
	p=$((26700 + RANDOM % 250))
	server srv_joinmid $p "$BASEDIR" "^3Joinmid^7 test"
	sleep 10   # the match is under way
	d="$WORK/joinmid"
	mkdir -p "$d/data"
	{
		echo 'set touch_join_dialog_auto 1; set cl_allow_uid2name 0; set developer 1; set fe_echo 1'
		echo 'alias xbeat "fe beat; defer 1 xbeat"'
		ui '{"uib":1,"t":"hello","id":1,"schema":"1.0","renderer":{"name":"test"},"caps":{"widthClass":"expanded"}}' | sed 's/^/alias xhello "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":4,"ev":"action","node":"home/tiles/mp","action":"mp"}' | sed 's/^/alias xmp "/; s/$/"/'
		ui "{\"uib\":1,\"t\":\"ev\",\"id\":5,\"ev\":\"value\",\"node\":\"mp/join/address\",\"value\":\"127.0.0.1:$p\"}" | sed 's/^/alias xaddr "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":6,"ev":"action","node":"mp/join/go","action":"join"}' | sed 's/^/alias xgo "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":7,"ev":"action","node":"join/spectate","action":"join.spectate"}' | sed 's/^/alias xspec "/; s/$/"/'
		echo "connect 127.0.0.1:$p"
		echo 'defer 30 "echo MARK classic; togglemenu 1"'
		echo 'defer 32 "togglemenu 0; fe attach 1; xbeat; xhello"'
		echo 'defer 34 "disconnect"'
		echo 'defer 36 "echo MARK mp; xmp"'
		echo 'defer 37 "xaddr"'
		echo 'defer 38 "echo MARK join; xgo"'
		echo 'defer 70 "echo MARK asked; touch_hud_state; uib_dump"'
		echo 'defer 71 "echo MARK spectate; xspec"'
		echo 'defer 76 "echo MARK after; touch_hud_state; uib_dump"'
		echo 'defer 77 "echo MARK pause; menu_showgamemenudialog"'
		echo 'defer 79 "echo MARK paused; uib_dump"'
		echo 'defer 80 "echo MARK end; quit"'
	} > "$d/data/tour.cfg"
	timeout -k 5 120 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
		+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
		+set _touch_assets_ready 1 +set touch_setup_done 1 +set touch_profile_done 1 +set touch_native_frontend 1 \
		+exec tour.cfg > "$d/client.log" 2>&1
	kill %1 2> /dev/null; wait 2> /dev/null
	python3 "$ROOT/tests/native-menu/check_joinmid.py" "$d/client.log" "127.0.0.1:$p" "Joinmid test" || fail=1
}

# A server with a long, coloured name behind a slow relay: one port the server list
# knows (a favourite), one typed into Multiplayer's address field.
JOINNAME_HOST='^1Red^7 ^x0f0Green^7 Test Server with a very long name indeed'
case_joinname() {
	local p q1 q2 d t
	p=$((26700 + RANDOM % 250)); q1=$((p + 300)); q2=$((p + 301))
	server srv_joinname $p "$BASEDIR" "$JOINNAME_HOST"   # this game code: no csprogs to download
	python3 "$ROOT/tests/native-menu/udp_delay.py" $p $q1,$q2 --info 2 --challenge 4 &
	d="$WORK/joinname"
	mkdir -p "$d/data"
	{
		echo 'set touch_join_dialog_auto 0; set cl_allow_uid2name 0; set developer 1; set fe_echo 1'
		# no master server: the list is the one favourite, asked directly
		for m in sv_master1 sv_master2 sv_master3 sv_master4 sv_masterextra1 sv_masterextra2 sv_masterextra3; do echo "$m \"\""; done
		echo "set net_slist_favorites \"127.0.0.1:$q1\""
		echo 'alias xbeat "fe beat; defer 1 xbeat"'
		ui '{"uib":1,"t":"hello","id":1,"schema":"1.0","renderer":{"name":"test"},"caps":{"widthClass":"expanded"}}' | sed 's/^/alias xhello "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":4,"ev":"action","node":"home/tiles/mp","action":"mp"}' | sed 's/^/alias xmp "/; s/$/"/'
		ui "{\"uib\":1,\"t\":\"ev\",\"id\":5,\"ev\":\"row\",\"node\":\"mp/servers\",\"row\":\"127.0.0.1:$q1\",\"kind\":\"select\"}" | sed 's/^/alias xsel "/; s/$/"/'
		ui "{\"uib\":1,\"t\":\"ev\",\"id\":6,\"ev\":\"row\",\"node\":\"mp/servers\",\"row\":\"127.0.0.1:$q1\",\"kind\":\"activate\"}" | sed 's/^/alias xact "/; s/$/"/'
		ui "{\"uib\":1,\"t\":\"ev\",\"id\":7,\"ev\":\"value\",\"node\":\"mp/join/address\",\"value\":\"127.0.0.1:$q2\"}" | sed 's/^/alias xaddr "/; s/$/"/'
		ui '{"uib":1,"t":"ev","id":8,"ev":"action","node":"mp/join/go","action":"join"}' | sed 's/^/alias xgo "/; s/$/"/'
		echo 'defer 1 "fe attach 1; xbeat; xhello"'
		echo 'defer 3 "echo MARK mp; xmp"'
		echo 'defer 9 "echo MARK list; uib_dump; xsel"'
		echo 'defer 10 "xact"'
		for t in 10.4 10.9 11.4 11.9 12.4 12.9 13.4; do echo "defer $t uib_dump"; done
		echo 'defer 24 "echo MARK listpause; menu_showgamemenudialog"'
		echo 'defer 25.5 "uib_dump"'
		echo 'defer 26 "echo MARK leave; disconnect"'
		echo 'defer 28 "xmp"'
		echo 'defer 29 "xaddr"'
		echo 'defer 30 "echo MARK typed; xgo"'
		for t in 30.4 30.9 31.4 31.9 32.4 32.9 33.4; do echo "defer $t uib_dump"; done
		echo 'defer 44 "echo MARK typedpause; menu_showgamemenudialog"'
		echo 'defer 45.5 "uib_dump"'
		echo 'defer 46 "echo MARK end; quit"'
	} > "$d/data/tour.cfg"
	timeout -k 5 100 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
		+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
		+set _touch_assets_ready 1 +set touch_setup_done 1 +set touch_profile_done 1 +set touch_native_frontend 1 \
		+exec tour.cfg > "$d/client.log" 2>&1
	kill %1 %2 2> /dev/null; wait 2> /dev/null
	python3 "$ROOT/tests/native-menu/check_joinname.py" "$d/client.log" "$JOINNAME_HOST" "127.0.0.1:$q1" "127.0.0.1:$q2" || fail=1
}

# Joining from spectating, with a native front end, on a stock server (DM and CTF).
# The fingers are at a tablet's size: 1920x1200 at 283 dpi, a 960x600 console;
# check_joinui.py first checks that "Swipe to join" is where they land.
JOINUI_KNOB="0.3966 0.8849"      # the knob, at the track's left end
JOINUI_END="0.6400 0.8849"       # past the end of its travel
JOINUI_TAP="0.5090 0.8849"       # the label
case_joinui() {
	local gt map p d finj
	command -v sdl2-config > /dev/null || { echo "SKIP joinui: sdl2-config (SDL2 headers) builds the finger shim"; return; }
	finj="$WORK/finj.so"
	[ -f "$finj" ] || cc -shared -fPIC -O2 -o "$finj" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl || { fail=1; return; }
	for gt in ctf dm; do
		if [ $gt = ctf ]; then map=implosion; else map=stormkeep; fi
		p=$((26700 + RANDOM % 250))
		d="$WORK/srv_joinui_$gt"; mkdir -p "$d"
		"${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$STOCKBASE" -userdir "$d" +set port $p +set sv_public 0 \
			+set bot_number 3 +set skill 1 +set g_warmup 0 +set g_dm 0 +set "g_$gt" 1 +set g_balance_teams 0 \
			+set timelimit_override 10 +set g_maplist $map +map $map > "$d/server.log" 2>&1 &
		local n; for n in $(seq 1 600); do grep -q "Server spawned" "$d/server.log" 2> /dev/null && break; sleep 0.1; done
		sleep 2
		d="$WORK/joinui_$gt"
		mkdir -p "$d/data"
		{
			echo 'set touch_join_dialog_auto 1; set cl_allow_uid2name 0; set cl_welcome 0; set developer 1; set fe_echo 1; set xt_go 0'
			echo 'set cl_touch_practice_pilot 0; set cl_touch_autoshoot 0; set cl_touch_aimassist 0'
			echo 'alias xbeat "fe beat; defer 1 xbeat"'
			ui '{"uib":1,"t":"hello","id":1,"schema":"1.0","renderer":{"name":"test"},"caps":{"widthClass":"expanded"}}' | sed 's/^/alias xhello "/; s/$/"/'
			ui '{"uib":1,"t":"ev","id":4,"ev":"action","node":"join/teams/red","action":"join.red"}' | sed 's/^/alias xred "/; s/$/"/'
			ui '{"uib":1,"t":"ev","id":5,"ev":"action","node":"join","action":"join.auto"}' | sed 's/^/alias xauto "/; s/$/"/'
			ui '{"uib":1,"t":"ev","id":6,"ev":"action","node":"join/spectate","action":"join.spectate"}' | sed 's/^/alias xspec "/; s/$/"/'
			echo 'fe attach 1; xbeat; xhello'
			echo "connect 127.0.0.1:$p"
			echo 'defer 32 "set xt_go 1"'
		} > "$d/data/tour.cfg"
		{
			mark() { echo "$1 cmd echo MARK $2"; echo "$1.05 cmd touch_hud_state rects"; echo "$1.1 cmd uib_dump"; }
			mark 0 connect
			# taps beside the dialog, where a scrim would be: top left, and the bottom middle
			echo "0.5 fdown 9 0.08 0.15"; echo "0.58 fup 9 0.08 0.15"
			echo "0.9 fdown 9 0.50 0.95"; echo "0.98 fup 9 0.50 0.95"
			mark 2 scrim
			if [ $gt = ctf ]; then echo "3 cmd xred"; else echo "3 cmd xauto"; fi
			mark 9 joined
			echo "10 cmd spec"
			mark 17 spectating
			if [ $gt = ctf ]; then
				# a swipe: the knob pushed past the end of the track; a team game opens the dialog
				echo "18 glide 7 $JOINUI_KNOB $JOINUI_END 0.45 up"
				mark 20 swiped
				echo "21 cmd xauto"
			else
				# a tap on the track joins a game without teams
				echo "18 fdown 7 $JOINUI_TAP"; echo "18.1 fup 7 $JOINUI_TAP"
				mark 20 swiped
			fi
			mark 27 rejoined
			echo "28 cmd quit"
		} > "$WORK/joinui_$gt.txt"
		LD_PRELOAD="$finj" XT_INJECT="$WORK/joinui_$gt.txt" timeout -k 5 100 "${RUN[@]}" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
			+vid_fullscreen 0 +vid_width 1920 +vid_height 1200 +set vid_touchscreen 1 +set vid_touchscreen_detected 1 \
			+set vid_touchscreen_xdpi 283 +set vid_touchscreen_ydpi 283 \
			+set _touch_assets_ready 1 +set touch_setup_done 1 +set touch_profile_done 1 +set touch_native_frontend 1 \
			+exec tour.cfg > "$d/client.log" 2>&1
		kill %1 2> /dev/null; wait 2> /dev/null
		python3 "$ROOT/tests/native-menu/check_joinui.py" "$d/client.log" $gt "$JOINUI_KNOB" || fail=1
	done
}

cases=("$@")
[ ${#cases[@]} -eq 0 ] && cases=(uid2name joinmid joinname joinui)
for c in "${cases[@]}"; do "case_$c"; done
[ $fail = 0 ] && echo "native menu live: all checks passed" || echo "native menu live: FAILED (OUT=<dir> keeps the logs)"
exit $fail
