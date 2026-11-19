#!/bin/bash
# Touch vote panel against a real local dedicated server, offscreen, with real
# SDL finger events (tests/touch-controls/finj.c). BASEDATA holds the stock pk3s.
# No display, no adb, no network beyond 127.0.0.1.
#
# Phase 1  create vote: the "i" card's "+" opens the server's allowed commands (a
#          virtual list scrolled by dragging), the gotomap argument picker lists
#          the server's maps, and the vote that is called shows in the card for its
#          caller, with the caller's own Yes already counted.
# Phase 2  late join: while that vote runs, a second client joins that only
#          follows server prints (no readable CSQC globals) and a third that reads
#          the globals. Both must list the running vote with its caller, and the
#          first taps the on-screen Yes for a vote somebody else called.
# Phase 3  match end: with the match ended, the map vote (and, in phase 4, the
#          gametype vote) has its own screen. The "i" card must not be open and
#          the chip must be gone, also after tapping where the chip was.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
BASEDATA="${BASEDATA:-/tmp/cx-fast-data/data}"
CPUS="${CPUS:-12-15}"
PHASES="${PHASES:-1 2 3 4}"
[ -x "$ENGINE" ] && [ -d "$BASEDATA" ] && command -v sdl2-config >/dev/null || { echo 'SKIP: set ENGINE and BASEDATA; SDL2 headers required'; exit 77; }
work="$(mktemp -d /tmp/godnotic-vote-live.XXXXXX)"
server_pid=
cleanup() {
    jobs -p | xargs -r kill 2>/dev/null || true
    if [ -n "${OUT:-}" ]; then mkdir -p "$OUT"; cp -a "$work"/. "$OUT/"; fi
    rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$work/base/data"
for f in "$BASEDATA"/*.pk3; do [ -e "$f" ] && ln -s "$f" "$work/base/data/"; done
ln -s "$ROOT/engine/data/xonotic-data.pk3dir" "$work/base/data/zzz-xonotic-data.pk3dir"
cc -shared -fPIC -O2 -U_GNU_SOURCE -o "$work/finj.so" "$ROOT/tests/touch-controls/finj.c" $(sdl2-config --cflags) -ldl
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY XAUTHORITY
failed=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; failed=1; }
# check <label> <log> <ERE> : the pattern must match somewhere in the client log
has() { if grep -Eq "$3" "$2"; then pass "$1"; else fail "$1"; grep -h 'Touch vote:\|TOUCHVOTE' "$2" | tail -15; fi; }
# lacks <label> <log> <ERE> : the pattern must match nowhere
lacks() { if grep -Eq "$3" "$2"; then fail "$1"; grep -E "$3" "$2" | head -5; else pass "$1"; fi; }

start_server() { # name, extra args...
    local name="$1"; shift
    local port
    port="$(python3 -c 'import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
    echo "$port" > "$work/$name.port"
    mkdir -p "$work/$name-srv"
    taskset -c "$CPUS" "$ENGINE" -dedicated -xonotic -basedir "$work/base" -userdir "$work/$name-srv" \
        +set port "$port" +set sv_public 0 +set sv_vote_gamestart 1 +set rcon_secure 0 +set rcon_password vote2 \
        +set sv_vote_timeout 300 +set bot_number 3 "$@" +map stormkeep > "$work/$name-server.log" 2>&1 &
    server_pid=$!
    for _ in $(seq 1 200); do
        grep -q 'Server spawned' "$work/$name-server.log" && return 0
        kill -0 "$server_pid" 2>/dev/null || { cat "$work/$name-server.log"; echo 'FAIL: server did not start'; exit 1; }
        sleep 0.1
    done
    echo 'FAIL: server start timed out'; exit 1
}
stop_server() { kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; server_pid=; }

# client <name> <server> <events text> [extra cfg lines]: runs in the foreground
client() {
    local name="$1" srv="$2" events="$3" extra="${4:-}" port
    port="$(cat "$work/$srv.port")"
    mkdir -p "$work/$name/data"
    printf '%s\n' "$events" > "$work/$name.events"
    cat > "$work/$name/data/tour.cfg" <<CFG
set developer 1
set cl_allow_uid2name 0
set touch_join_dialog_auto 0
set xt_go 0
set rcon_password vote2
set rcon_secure 0
$extra
connect 127.0.0.1:$port
defer 12 "set xt_go 1"
CFG
    LD_PRELOAD="$work/finj.so" XT_INJECT="$work/$name.events" timeout 120 taskset -c "$CPUS" "$ENGINE" -xonotic \
        -basedir "$work/base" -userdir "$work/$name" +set vid_width 800 +set vid_height 600 \
        +set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set touch_profile_done 1 \
        +exec tour.cfg > "$work/$name.log" 2>&1 || { tail -40 "$work/$name.log"; echo "FAIL: client $name"; failed=1; }
}

CHIP='0.6366 0.0428'
TAP() { echo "$1 fdown $2 $3"; echo "$(echo "$1 + 0.4" | bc) fup $2 $3"; }

if [[ " $PHASES " == *" 1 "* || " $PHASES " == *" 2 "* ]]; then
    # Called votes are listed for everybody and for the caller. sv_vote_debug lets the
    # bots count as voters, so one human Yes does not end it: it keeps running.
    start_server v12 +set sv_vote_debug 1
    A_EVENTS="1 cmd touch_join_cancel
1.5 cmd togglemenu 0
4 fdown 1 0.6366 0.0428
4.4 fup 1 0.6366 0.0428
7 fdown 2 0.5788 0.1417
7.4 fup 2 0.5788 0.1417
10 cmd touch_vote_state
12 glide 3 0.30 0.65 0.30 0.30 0.8 up
14 cmd touch_vote_state
16 glide 4 0.30 0.30 0.30 0.65 0.8 up
18 cmd touch_vote_state
20 fdown 5 0.30 0.49
20.4 fup 5 0.30 0.49
23 cmd touch_vote_state
25 fdown 6 0.30 0.255
25.4 fup 6 0.30 0.255
29 cmd touch_vote_state
34 cmd touch_vote_state
60 cmd touch_vote_state
62 cmd quit"
    client A v12 "$A_EVENTS" &
    a_pid=$!
    if [[ " $PHASES " == *" 2 "* ]]; then
        for _ in $(seq 1 600); do grep -q 'calls a vote for' "$work/v12-server.log" && break; sleep 0.2; done
        grep -q 'calls a vote for' "$work/v12-server.log" || fail 'phase 2: no vote was called by client A'
        # B: prints only. The on-screen Yes is where touch_vote_state says (qyes).
        B_EVENTS="1 cmd touch_join_cancel
1.5 cmd togglemenu 0
5 cmd togglemenu 0
7 cmd togglemenu 0
8 cmd touch_vote_state
10 cmd touch_vote_state
11 fdown 1 QYESX QYESY
11.4 fup 1 QYESX QYESY
15 cmd touch_vote_state
17 fdown 2 0.6366 0.0428
17.4 fup 2 0.6366 0.0428
19 cmd touch_vote_state
22 cmd quit"
        C_EVENTS="1 cmd touch_join_cancel
1.5 cmd togglemenu 0
3 cmd join
5 cmd togglemenu 0
7 cmd togglemenu 0
8 cmd touch_vote_state
11 cmd touch_vote_state
17 cmd touch_vote_state
20 cmd touch_vote_state
22 cmd quit"
        # Placeholder rect from the 800x600 layout: x = (800 - 2w - gap) / 2 + w / 2, y = 0.235 * 600 + h / 2
        # with w = 0.24 * 600 = 144 (h = 63): YES centre is (800 - 144 - 21) / 2 = 317 .. 317 + 72 = 389; y = 141 + 31 = 172.
        B_EVENTS="${B_EVENTS//QYESX/0.4863}"; B_EVENTS="${B_EVENTS//QYESY/0.2867}"
        client B v12 "$B_EVENTS" 'set _cl_touch_vote_noglobals 1' &
        b_pid=$!
        client C v12 "$C_EVENTS" &
        c_pid=$!
        wait "$b_pid" || true
        wait "$c_pid" || true
    fi
    wait "$a_pid" || true
    stop_server
    if [[ " $PHASES " == *" 1 "* ]]; then
        echo '== phase 1: create vote'
        has 'chip and create picker' "$work/A.log" 'Touch vote: create picker opened'
        has 'allowed commands received' "$work/A.log" 'Touch vote: [1-9][0-9]* available commands'
        has 'create list is a virtual list (5 rows of 16 commands)' "$work/A.log" 'TOUCHVOTE .*kind=1 .*rows=5 .*commands=16'
        has 'drag scrolls list' "$work/A.log" 'TOUCHVOTE .*kind=1 .*scroll=[1-9]'
        has 'drag back without selecting' "$work/A.log" 'TOUCHVOTE .*kind=1 .*scroll=0 .*commands=[1-9]'
        has 'map command selected' "$work/A.log" 'Touch vote: selected command gotomap'
        has 'server map picker populated' "$work/A.log" 'TOUCHVOTE .*kind=2 .*maps=[1-9]'
        has 'touch submits a map vote' "$work/A.log" 'Touch vote: cmd vote call gotomap [a-zA-Z0-9_.-]+'
        has 'server accepted called vote' "$work/A.log" 'calls a vote for'
        has 'caller sees own vote with its own Yes' "$work/A.log" 'TOUCHVOTE .*kind=0 .*call=1 .*mine=1 '
        has 'caller still sees it a minute later' "$work/A.log" 'TOUCHVOTE .*call=1 .*caller=.* mine=1 quick=0'
    fi
    if [[ " $PHASES " == *" 2 "* ]]; then
        echo '== phase 2: late join with a vote running'
        has 'late joiner (prints only) asked the server' "$work/B.log" 'Touch vote: print event 1 \(Player#[0-9]+ / gotomap '
        has 'late joiner (prints only) lists the running vote' "$work/B.log" 'TOUCHVOTE .*call=1 src=prints caller=Player#[0-9]+ mine=0 quick=1'
        has 'late joiner opened the vote' "$work/B.log" 'Touch vote: vote open \(prints\): gotomap'
        has 'late joiner answers with the on-screen Yes' "$work/B.log" 'Touch vote: quick yes'
        has 'late joiner sees own answer, panel no longer asks' "$work/B.log" 'TOUCHVOTE .*call=1 .* mine=1 quick=0 '
        has 'late joiner'"'"'s Yes is counted by the server' "$work/C.log" 'TOUCHVOTE .*call=1 src=csqc .* yes=[2-9] no=0'
        has 'late joiner finds the vote in the i card' "$work/B.log" 'TOUCHVOTE .*open=1 card=1 kind=0 .*call=1'
        has 'late joiner (CSQC globals) lists the running vote and its caller' "$work/C.log" 'TOUCHVOTE .*call=1 src=csqc caller=Player#[0-9]+ mine=0'
        has 'a player who joined meanwhile has the i chip and the vote too' "$work/C.log" 'TOUCHVOTE mode=(playing|dead) chip=1 .*call=1 src=csqc'
    fi
fi

for phase in 3 4; do
    [[ " $PHASES " == *" $phase "* ]] || continue
    extra=()
    [ "$phase" = 4 ] && extra=(+set sv_vote_gametype 1)
    start_server "e$phase" "${extra[@]}"
    E_EVENTS="1 cmd touch_join_cancel
1.5 cmd togglemenu 0
3 cmd join
3.5 cmd togglemenu 0
4 fdown 1 0.6366 0.0428
4.4 fup 1 0.6366 0.0428
6 cmd touch_vote_state
6.5 cmd set rcon_password vote2
7 cmd rcon set _endmatch 1
8 cmd touch_vote_state"
    for t in 10 14 18 22 26 30 34 38; do
        E_EVENTS+="
$t cmd touch_vote_state
$t cmd prvm_globalget client gametypevote _gtv; wait; echo GTV=\$_gtv
$((t + 1)) fdown 3 0.6366 0.0428
$((t + 1)).4 fup 3 0.6366 0.0428
$((t + 1)).8 cmd touch_vote_state"
    done
    E_EVENTS+="
42 cmd quit"
    client "E$phase" "e$phase" "$E_EVENTS"
    stop_server
    log="$work/E$phase.log"
    echo "== phase $phase: $([ $phase = 3 ] && echo map vote || echo gametype vote) on its own screen, not in the i panel"
    has 'before the end the chip is there and the card opens' "$log" 'TOUCHVOTE mode=(playing|dead) chip=1 .*open=1 card=1 kind=0'
    has 'the match end reached the on-screen vote' "$log" 'TOUCHVOTE mode=mapvote '
    [ "$phase" = 4 ] && has 'the screen was the gametype vote' "$log" 'GTV=1'
    [ "$phase" = 3 ] && lacks 'the screen was the map vote, not a gametype vote' "$log" 'GTV=1'
    lacks 'no chip, card or Yes/No over the vote screen' "$log" 'TOUCHVOTE mode=(mapvote|intermission) .*(chip=1|open=1|card=1|quick=1)'
    lacks 'no called vote listed at match end' "$log" 'TOUCHVOTE mode=(mapvote|intermission) .*call=1'
done

[ "$failed" = 0 ] && echo 'touch vote live: all checks passed' || { echo 'touch vote live: FAILED'; exit 1; }
