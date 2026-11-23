#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The chat feed's player events, the tappable icons and the scrollable chat log,
# in a real offscreen client against a real dedicated server (touch_chatlog.c).
#
#   events      a second client joins, plays, spectates, renames and leaves: the
#               first client's log has each as its own kind, worded the same way
#   tap         the feed's targets are at least 44 dp, a tap on a name opens that
#               player's actions, a tap on an icon runs it (clear, log)
#   scroll      with a long log open: the mouse wheel (Key_Event), a drag and a
#               fling (touch_chatlog_ptr, the path the touch layer forwards) move it
#   retention   after the feed's lines have faded the log still has every line
#
# Screenshots (OUT=<dir>): events-feed.png, events-log.png, events-popup.png, ...
#
#   BASEDIR=<Godnotic basedir> scripts/test-chat-events.sh
# Environment: ENGINE (default build/bin/xonotic; also the server, -dedicated),
# LIBS (extra LD_LIBRARY_PATH), OUT (keep the logs and screenshots), SIZE (WxH, default 640x360).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
if [ ! -x "$ENGINE" ] || [ ! -d "${BASEDIR:-/nonexistent}/data" ]; then
	echo "SKIP: set ENGINE and BASEDIR"
	exit 0
fi
SIZE="${SIZE:-640x360}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-chatev.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	if [ -n "${OUT:-}" ]; then
		mkdir -p "$OUT"
		cp -r "$WORK"/. "$OUT/" 2> /dev/null
		find "$WORK" -name '*.png' -o -name '*.jpg' -o -name '*.tga' 2> /dev/null | while read -r f; do cp "$f" "$OUT/$(basename "$f")" 2> /dev/null; done
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }
strip() { sed 's/\x1b\[[0-9;]*m//g' "$@"; }

server() { # dir port
	local d=$1 port=$2 n
	mkdir -p "$d/data"
	"$ENGINE" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$d" +set port "$port" +set sv_public 0 +set g_maplist stormkeep \
		+set bot_number 2 +set minplayers 0 +set sv_maxidle_playertospectator 0 +map stormkeep > "$d.log" 2>&1 &
	for n in $(seq 1 900); do grep -q "Server spawned" "$d.log" 2> /dev/null && break; sleep 0.1; done
	sleep 1
}
# client dir name first-command extra...: an offscreen client that runs the first
# command, then polls data/step.cfg every quarter second (a step file is run once:
# step <n> is written, and the poll moves on to the next number)
client() {
	local d=$1 name=$2 first=$3 w=${SIZE%x*} h=${SIZE#*x}
	shift 3
	mkdir -p "$d/data"
	printf 'seta touch_profile_done 1\nseta _cl_name %s\nseta cl_allow_uid2name 0\n' "$name" > "$d/data/autoexec.cfg"
	cat > "$d/data/run.cfg" <<EOF
set xt_n 0
alias xt_poll "exec step\$xt_n.cfg; defer 0.25 xt_poll"
defer 2 "$first"
defer 3 xt_poll
defer 400 quit
EOF
	timeout 420 "$ENGINE" -xonotic -nosound -basedir "$BASEDIR" -userdir "$d" +vid_fullscreen 0 +vid_width "$w" +vid_height "$h" \
		+set _touch_assets_ready 1 +set touch_joinfail_dialog 0 "$@" +exec run.cfg > "$d.log" 2>&1 &
}
# step dir n "commands..." : the client runs these (one per line) when it reaches step n, then moves to n+1
step() {
	local d=$1 n=$2
	shift 2
	{
		printf '%s\n' "$@"
		printf 'set xt_n %d\n' $((n + 1))
	} > "$d/data/step$n.cfg.tmp"
	mv "$d/data/step$n.cfg.tmp" "$d/data/step$n.cfg"
}
waitfor() { # log pattern seconds
	local n
	for n in $(seq 1 $(($3 * 10))); do strip "$1" 2> /dev/null | grep -aq "$2" && return 0; sleep 0.1; done
	return 1
}
# the answer to touch_chatlog_state / touch_chatlog_dump: wait for the END marker after a mark line
marker=0
ask() { # dir step-number command... -> prints the output lines to $d.ask
	local d=$1 n=$2 cmd=$3 m
	marker=$((marker + 1)); m="ASK-$marker"
	step "$d" "$n" "echo $m-begin" "$cmd" "echo $m-end"
	if ! waitfor "$d.log" "$m-end" 30; then bad "no answer to '$cmd' (log: $d.log)"; : > "$d.ask"; return 1; fi
	strip "$d.log" | sed -n "/$m-begin/,/$m-end/p" > "$d.ask"
}
state() { grep -a "^CHATLOG-STATE" "$1.ask" | tail -1; }
field() { state "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }

tn=0 # the first client's step counter: T runs commands in it, TA asks and reads the answer
T() { step "$d/t" $tn "$@"; tn=$((tn + 1)); }
TA() { ask "$d/t" $tn "$@"; local r=$?; tn=$((tn + 1)); return $r; }

port=$((27100 + RANDOM % 400))
d="$WORK/run"; mkdir -p "$d"
server "$d/sv" $port

# ------------------------------------------------------------------ events
client "$d/t" Tester "connect 127.0.0.1:$port" +set touch_chatlog_fade 6
if ! waitfor "$d/t.log" "Tester.*connected" 120; then bad "the first client never joined (log: $d/t.log)"; exit 1; fi
# the front end's join card is up for a spectator: join the match and close it
T "touch_join join" "cmd join"
waitfor "$d/t.log" "Tester.*is now playing" 60 || bad "the first client never started playing (log: $d/t.log)"
sleep 8 # past the 3 second rename grace
T "touch_join_answered"
TA "touch_chatlog_state"
if [ "$(field "$d/t" dest)" != 0 ]; then T "togglemenu 0"; fi
client "$d/g" Guest "connect 127.0.0.1:$port"
waitfor "$d/g.log" "Guest.*connected" 120 || bad "the guest never joined (log: $d/g.log)"
sleep 3
step "$d/g" 0 "cmd join"; sleep 6
step "$d/g" 1 "cmd spectate"; sleep 6
step "$d/g" 2 "cmd join"; sleep 6
step "$d/g" 3 "name Gnew"; sleep 8
step "$d/g" 4 "quit"
sleep 6

TA "touch_chatlog_dump"
plain() { sed -n 's/^CHATLOG [0-9]* \([a-z]*\) \([^ ]*\) | \(.*\)$/\1|\2|\3/p' "$d/t.ask"; }
plain > "$d/t.events"
check_event() { # kind name text
	if grep -aqxF "$1|$2|$3" "$d/t.events"; then ok "events: $1 line \"$3\""; else bad "events: no $1 line \"$3\" (have: $(tr '\n' ';' < "$d/t.events" | cut -c1-400))"; fi
}
check_event join Guest "Guest joined the server"
check_event play Guest "Guest started playing"
check_event spec Guest "Guest moved to spectators"
check_event rename Gnew "Guest is now known as Gnew"
check_event leave Gnew "Gnew left the server (Disconnect by user)"
# the order the lines came in
order=$(grep -an "Guest joined the server\|Guest started playing\|Guest moved to spectators\|Guest is now known as Gnew\|Gnew left the server (Disconnect by user)" "$d/t.ask" | sed 's/.*| //' | tr '\n' ';')
case "$order" in
	"Guest joined the server;Guest started playing;Guest moved to spectators;Guest started playing;Guest is now known as Gnew;Gnew left the server (Disconnect by user);"*) ok "events: in the order they happened" ;;
	*) bad "events: wrong order: $order" ;;
esac
if grep -aqE "^[a-z]+\|[^|]*\|.*Tester" "$d/t.events"; then ok "events: the first client's own join is in the log"; else bad "events: the first client's own join is missing"; fi

# ------------------------------------------------------------------ feed + screenshot
T "screenshot events-empty.png"
sleep 3
T "touch_chatlog_fade 600" "touch_chatlog_inject 1 Guest^7: hello everyone this is a longer chat line that has to wrap over more than one row of the feed" "touch_chatlog_inject 3 ^1Red^7Fox^3 is now playing on the ^1Red^3 team" "touch_chatlog_inject 3 ^4Blue^7Bird^3 switched to the ^4Blue^3 team"
sleep 2
T "screenshot events-feed.png"
sleep 2
TA "touch_chatlog_state"
if ! grep -aq "^CHATLOG-TARGET \(log\|clear\|copy\|download\|share\) " "$d/t.ask" && grep -aq "^CHATLOG-TARGET row " "$d/t.ask"; then ok "tap: the feed in play has NO icon strip (lines only, tappable)"; else bad "tap: icons over plain gameplay or no rows: $(grep -a TARGET "$d/t.ask" | head -5 | tr '\n' ';')"; fi
if grep -aq "^CHATLOG-TARGET name " "$d/t.ask"; then ok "tap: player names on the feed are targets"; else bad "tap: no name targets on the feed"; fi
w=${SIZE%x*}; h=${SIZE#*x}
[ -n "${QUICK:-}" ] && { T "screenshot events-feed2.png"; sleep 4; echo "quick: screenshots only"; exit 0; }
# tap a feed line: the log opens (the only way in during play besides the pause menu)
read -r lx ly < <(grep -a "^CHATLOG-TARGET row " "$d/t.ask" | head -1 | awk '{print $4, $5}')
T "touch_chatlog_ptr down $lx $ly" "defer 0.1 \"touch_chatlog_ptr up $lx $ly\"" "defer 1 \"screenshot events-log.png\""
sleep 3
TA "touch_chatlog_state"
if [ "$(field "$d/t" panel)" = 1 ]; then ok "tap: a tap on a feed line opens the log"; else bad "tap: the log did not open: $(state "$d/t")"; fi
if grep -aq "^CHATLOG-TARGET close " "$d/t.ask" && grep -aq "^CHATLOG-TARGET chip 2 " "$d/t.ask"; then ok "tap: the log has close and filter targets"; else bad "tap: log header targets missing"; fi
small=$(grep -a "^CHATLOG-TARGET \(close\|clear\|copy\|download\|share\) " "$d/t.ask" | awk -v w="$w" -v h="$h" '{ if ($6 * w < 43.5 || $7 * h < 43.5) print $2 }' | tr '\n' ' ')
if [ -z "$small" ] && grep -aq "^CHATLOG-TARGET share " "$d/t.ask"; then ok "tap: the log's icons (close, clear, copy, download, share) are targets of at least 44 px"; else bad "tap: log icon targets under 44 px or missing: $small"; fi
# the engine-level finger path (what a finger does under ANY server's CSQC, stock included): a finger far from
# the panel is taken only while the log is open, and a press outside the panel closes it
TA "touch_chatlog_finger down 7 0.99 0.99"
T "touch_chatlog_finger up 7 0.99 0.99"
TA "touch_chatlog_state"
if [ "$(field "$d/t" panel)" = 0 ]; then ok "finger: a press outside the open log closes it (engine finger path)"; else bad "finger: panel still open: $(state "$d/t")"; fi
T "touch_chatlog_open"
sleep 2
TA "touch_chatlog_state"
read -r lx2 ly2 < <(grep -a "^CHATLOG-TARGET chip 2 " "$d/t.ask" | head -1 | awk '{print $4, $5}')
T "touch_chatlog_finger down 5 $lx2 $ly2" "touch_chatlog_finger up 5 $lx2 $ly2"
sleep 2
TA "touch_chatlog_state"
if [ "$(field "$d/t" filter)" = 2 ]; then ok "finger: a finger tap on the Events chip is taken and acts"; else bad "finger: chip not pressed by a finger: $(state "$d/t")"; fi
T "touch_chatlog_close" "touch_chatlog_open"
sleep 1
TA "touch_chatlog_state"
if [ "$(field "$d/t" filter)" = 0 ]; then ok "filter: reopening starts on All"; else bad "filter: reopening did not select All: $(state "$d/t")"; fi
# a tap on a name in the log: that player's actions
read -r nx ny < <(grep -a "^CHATLOG-TARGET name " "$d/t.ask" | head -1 | awk '{print $4, $5}')
T "touch_chatlog_ptr down $nx $ny" "defer 0.1 \"touch_chatlog_ptr up $nx $ny\"" "defer 1.5 \"screenshot events-popup.png\""
sleep 3
TA "touch_chatlog_state"
if [ "$(field "$d/t" popup)" = 1 ] && [ "$(field "$d/t" popup_name)" != "-" ]; then ok "tap: a tap on a name opens that player's actions ($(field "$d/t" popup_name))"; else bad "tap: no actions after tapping a name: $(state "$d/t")"; fi
read -r px py < <(grep -a "^CHATLOG-TARGET pop 0 " "$d/t.ask" | head -1 | awk '{print $4, $5}')
T "touch_chatlog_ptr down $px $py" "defer 0.1 \"touch_chatlog_ptr up $px $py\""
sleep 2
TA "touch_chatlog_state"
if [ "$(field "$d/t" popup)" = 0 ] && [ "$(field "$d/t" dest)" = 1 ]; then ok "tap: Mention closes the actions and opens the chat line"; else bad "tap: Mention did nothing: $(state "$d/t")"; fi
T "touch_chatlog_open"
sleep 1

# ------------------------------------------------------------------ scrolling
{
	for i in $(seq 1 150); do echo "touch_chatlog_inject 1 Typer^7: line $i of a long log, with enough words in it to need a second row once the panel is only so wide, number $i"; done
} > "$d/t/data/many.txt"
T "touch_chatlog_filter all" "exec many.txt"
sleep 3
TA "touch_chatlog_state"
c0=$(field "$d/t" content); b0=$(field "$d/t" body); p0=$(field "$d/t" pos)
if awk -v c="$c0" -v b="$b0" 'BEGIN{exit !(c > b * 5)}'; then ok "scroll: 150 more lines make a log many screens tall (content $c0, view $b0)"; else bad "scroll: the log is not tall: $(state "$d/t")"; fi
T "uib_input key MWHEELUP" "uib_input key MWHEELUP"
sleep 2
TA "touch_chatlog_state"
p1=$(field "$d/t" pos)
if awk -v a="$p0" -v b="$p1" 'BEGIN{exit !(b > a + 20)}'; then ok "scroll: the mouse wheel scrolls back ($p0 -> $p1)"; else bad "scroll: wheel did not scroll: $p0 -> $p1"; fi
T "uib_input key MWHEELDOWN" "uib_input key MWHEELDOWN" "uib_input key MWHEELDOWN" "uib_input key MWHEELDOWN"
sleep 2
TA "touch_chatlog_state"
p2=$(field "$d/t" pos)
if awk -v b="$p2" 'BEGIN{exit !(b == 0)}'; then ok "scroll: the wheel stops at the newest line"; else bad "scroll: wheel down left pos $p2"; fi
# a slow drag down: the content follows the finger
T "touch_chatlog_ptr down 0.5 0.35" "defer 0.4 \"touch_chatlog_ptr move 0.5 0.45\"" "defer 0.8 \"touch_chatlog_ptr move 0.5 0.55\"" "defer 1.2 \"touch_chatlog_ptr move 0.5 0.65\"" "defer 1.5 \"echo DRAGDONE\""
waitfor "$d/t.log" "DRAGDONE" 60 || bad "the drag never finished"
TA "touch_chatlog_state"
p3=$(field "$d/t" pos)
exp=$(awk -v h="$(field "$d/t" conh)" 'BEGIN{printf "%d", 0.30 * h}')
if awk -v a="$p3" -v e="$exp" 'BEGIN{exit !(a > e * 0.7 && a < e * 1.3)}'; then ok "scroll: dragging 30% of the screen down moves the log about that far ($p3 of ~$exp units)"; else bad "scroll: drag moved $p3, expected about $exp"; fi
T "touch_chatlog_ptr up 0.5 0.65"
sleep 3
TA "touch_chatlog_state"
p4=$(field "$d/t" pos)
if awk -v a="$p3" -v b="$p4" 'BEGIN{exit !(b >= a && b < a + 40)}' && [ "$(field "$d/t" vel)" = "0.0" ]; then ok "scroll: a drag that rests before lifting does not fling ($p3 -> $p4)"; else bad "scroll: unexpected motion after a resting lift: $p3 -> $p4 $(state "$d/t")"; fi
# a quick flick: down 0.25 in 0.12 s, lift while moving: it keeps going
T "touch_chatlog_scroll -1000" "defer 0.5 \"touch_chatlog_ptr down 0.5 0.3\"" "defer 0.6 \"touch_chatlog_ptr move 0.5 0.4\"" "defer 0.7 \"touch_chatlog_ptr move 0.5 0.5\"" "defer 0.8 \"touch_chatlog_ptr move 0.5 0.6\"" "defer 0.85 \"touch_chatlog_ptr up 0.5 0.62\"" "defer 2.5 \"echo FLICKDONE\""
waitfor "$d/t.log" "FLICKDONE" 60
TA "touch_chatlog_state"
p5=$(field "$d/t" pos)
if awk -v a="$p5" 'BEGIN{exit !(a > 150)}'; then ok "scroll: a flick coasts on after the finger lifts (log scrolled to $p5)"; else bad "scroll: flick went nowhere: $p5"; fi
if [ "$(field "$d/t" vel)" = "0.0" ]; then ok "scroll: and the coast has stopped by itself"; else bad "scroll: still moving: $(state "$d/t")"; fi
T "screenshot events-scrolled.png" "touch_chatlog_filter events"
sleep 3
TA "touch_chatlog_state"
if [ "$(field "$d/t" filter)" = 2 ]; then ok "scroll: the Events filter is on"; fi
T "screenshot events-filtered.png" "touch_chatlog_filter all" "touch_chatlog_close"
sleep 3

# ------------------------------------------------------------------ remote typing
# Another player typing is a line about THEM; my input holds only what I type.
T "touch_chatlog_close" "_touch_typing_sim Bob" "messagemode" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE" "uib_input key BACKSPACE"
sleep 3
TA "echo TYPING=[\$_touch_typing] DBG=[\$_touch_mention_dbg]"
if grep -aq "TYPING=\[Bob is typing" "$d/t.ask"; then ok "typing: the indicator names the remote player"; else bad "typing: no indicator: $(grep -a TYPING "$d/t.ask")"; fi
if grep -aq "buf= " "$d/t.ask" || grep -aq "buf=  *mouse" "$d/t.ask"; then ok "typing: my empty input stays empty while Bob types"; else bad "typing: my input is not empty: $(grep -a DBG "$d/t.ask")"; fi
T "uib_input key ESCAPE" "messagemode mine"
sleep 3
TA "echo TYPING=[\$_touch_typing] DBG=[\$_touch_mention_dbg]"
if grep -aq "buf=mine " "$d/t.ask" && ! grep -aq "buf=[^ ]*Bob" "$d/t.ask"; then ok "typing: my input keeps only my own text while Bob types"; else bad "typing: my input changed: $(grep -a DBG "$d/t.ask")"; fi
T "uib_input key ESCAPE" "_touch_typing_sim \"\""
sleep 1

# ------------------------------------------------------------------ retention
T "touch_chatlog_fade 2" "touch_chatlog_inject 3 ^2Late^7Comer^3 connected"
sleep 6
TA "touch_chatlog_dump"
nlines=$(sed -n 's/^CHATLOG-END //p' "$d/t.ask")
if [ "${nlines:-0}" -ge 150 ] && grep -aq "LateComer joined the server" "$d/t.ask"; then ok "retention: $nlines lines are still in the log after the feed's lines faded"; else bad "retention: the log lost lines ($nlines)"; fi
TA "touch_chatlog_state"
if ! grep -aq "^CHATLOG-TARGET row " "$d/t.ask"; then ok "retention: the feed itself is empty (every line faded)"; else bad "retention: feed rows are still up: $(grep -a 'TARGET row' "$d/t.ask" | head -2 | tr '\n' ';')"; fi
T "touch_chatlog_open" "defer 1.5 \"screenshot events-after.png\""
sleep 4
TA "touch_chatlog_state"
if [ "$(field "$d/t" panel)" = 1 ]; then ok "retention: the log opens again with everything in it"; else bad "retention: the log did not open: $(state "$d/t")"; fi
T "touch_chatlog_clear"
sleep 2
TA "touch_chatlog_dump"
if [ "$(sed -n 's/^CHATLOG-END //p' "$d/t.ask")" = 0 ]; then ok "tap: the clear action empties the log"; else bad "tap: the log was not cleared"; fi
T "quit"
sleep 3

[ $fail = 0 ] && echo "chat-events: all checks passed"
exit $fail
