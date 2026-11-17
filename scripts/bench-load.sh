#!/usr/bin/env bash
# Load-time benchmark: process start to main menu, menu to first game frame of
# a local match, and a second client joining a server over loopback, with and
# without simulated latency. Each scenario runs RUNS times; the summary gives
# the median and the spread (min-max) of every LOADTIME figure the engine
# prints (touch_loadtime 1, engine/darkplaces/touch_loadtime.c).
# world_presented is measured after the video swap, with game input and the
# console closed; an in-game overlay can still cover the rendered world.
# world_preview is the same swap during the join, while the load still runs:
# the first frame of the world on screen.
#
#   scripts/bench-load.sh [-n RUNS] [-c warm|cold] [-o OUTDIR] [-e "extra +args"] SCENARIO...
#
# SCENARIO:
#   menu              process start -> first frame of the main menu
#   cold-menu         process start -> first menu frame with an empty userdir
#   reconnect:NAME    disconnect and reconnect to the same loopback server
#   vote:NAME         end a running match -> first observed map-vote screen
#   map:NAME          main menu -> first game frame of `map NAME` (local game)
#   join:NAME[:MS]    a dedicated server runs NAME; a second process connects
#                     to 127.0.0.1 from its main menu; MS adds that much
#                     one-way delay to loopback (tc netem in a user+network
#                     namespace, so RTT = 2*MS). LOSS=<percent> adds loss,
#                     NETEM="reorder 25% 50% duplicate 2%" any other netem
#                     options (docs/NETWORK.md).
#
# Environment:
#   BASEDIR   game data dir holding data/ (required)
#   ENGINE    engine binary (default: build/bin/xonotic of this checkout)
#   LIBS      extra LD_LIBRARY_PATH (e.g. a dir with libjpeg.so.62)
#   W, H      window size (default 1280x720, offscreen SDL)
#   MENU_IDLE seconds spent in the menu before the load starts (default 1)
#   SV_ENGINE dedicated server binary for join: (default: ENGINE -dedicated)
#   SV_EXTRA  extra +args for that server (e.g. "+set net_reliable_window 0")
#
# Cold runs evict the pk3s, the engine and its data dir from the page cache
# with posix_fadvise(DONTNEED) (no root needed); warm runs read them once
# first. Every run gets a fresh copy of a userdir made by one throwaway run,
# so config.cfg exists as on a real install.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNS=5
CACHE=warm
OUT=""
EXTRA=""
while getopts "n:c:o:e:h" opt; do
	case $opt in
		n) RUNS=$OPTARG ;;
		c) CACHE=$OPTARG ;;
		o) OUT=$OPTARG ;;
		e) EXTRA=$OPTARG ;;
		*) sed -n '2,33p' "$0"; exit 1 ;;
	esac
done
shift $((OPTIND - 1))
[ $# -gt 0 ] || { sed -n '2,33p' "$0"; exit 1; }
: "${BASEDIR:?set BASEDIR to a game data dir (holding data/)}"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
W="${W:-1280}"; H="${H:-720}"
MENU_IDLE="${MENU_IDLE:-1}"
OUT="${OUT:-$(mktemp -d "${TMPDIR:-/tmp}/bench-load.XXXXXX")}"
mkdir -p "$OUT"

export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy

evict() {
	python3 - "$@" <<'EOF'
import os, sys
for root in sys.argv[1:]:
    paths = [root] if os.path.isfile(root) else []
    for d, _, files in os.walk(root, followlinks=True):
        paths += [os.path.join(d, f) for f in files]
    for p in paths:
        try:
            fd = os.open(os.path.realpath(p), os.O_RDONLY)
            os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
            os.close(fd)
        except OSError:
            pass
EOF
}
warm() {
	find -L "$@" -type f -print0 2>/dev/null | xargs -0 cat > /dev/null 2>&1 || true
}
prepare_cache() {
	if [ "$CACHE" = cold ]; then
		evict "$BASEDIR/data/" "$ENGINE"
	else
		warm "$BASEDIR/data/" "$ENGINE"
	fi
}

# engine command line shared by every run
engine_args() { # userdir
	echo -xonotic -basedir "$BASEDIR" -userdir "$1" +exec xonotic.cfg +vid_fullscreen 0 \
		+vid_width "$W" +vid_height "$H" +set _touch_assets_ready 1 +set touch_loadtime 1 $EXTRA
}

# Stop as soon as the milestone is logged; do not rely on the quit command.
run_until() { # log marker occurrences command...
	local log=$1 marker=$2 count=$3 pid i found=0
	shift 3
	"$@" > "$log" 2>&1 & pid=$!
	for i in $(seq 1 1800); do
		if [ "$(grep -c "$marker" "$log" 2>/dev/null || true)" -ge "$count" ]; then found=1; break; fi
		kill -0 "$pid" 2>/dev/null || break
		sleep 0.1
	done
	kill "$pid" 2>/dev/null || true
	wait "$pid" 2>/dev/null || true
	[ "$found" = 1 ] || { echo "missing $marker in $log" >&2; return 1; }
}
export -f run_until

# Timestamp the start marker and the first HUD observation of mapvote using a
# monotonic clock. The HUD is polled every 50 ms, subject to frame scheduling.
run_vote_client() {
	local log=$1; shift
	python3 - "$log" "$@" <<'PYVOTE'
import os, selectors, subprocess, sys, time
log, cmd = sys.argv[1], sys.argv[2:]
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
sel = selectors.DefaultSelector(); sel.register(p.stdout, selectors.EVENT_READ)
start = None; pending = b''; deadline = time.monotonic() + 180; result = None
with open(log, 'wb', buffering=0) as out:
    while time.monotonic() < deadline:
        if not sel.select(0.1):
            if p.poll() is not None: break
            continue
        chunk = os.read(p.stdout.fileno(), 65536)
        if not chunk: break
        now = time.monotonic(); out.write(chunk); pending += chunk
        lines = pending.split(b'\n'); pending = lines.pop()
        for line in lines:
            if b'BENCH_VOTE_START' in line: start = now
            if start is not None and b'TOUCHHUD mode=mapvote ' in line:
                result = (now - start) * 1000
                out.write(('LOADTIME vote_ready load=%.1f\n' % result).encode()); break
        if result is not None: break
    p.terminate()
    try: p.wait(timeout=5)
    except subprocess.TimeoutExpired: p.kill(); p.wait()
if result is None:
    print('vote screen not observed in ' + log, file=sys.stderr); sys.exit(1)
PYVOTE
}
export -f run_vote_client

TEMPLATE="$OUT/userdir-template"
make_template() {
	[ -d "$TEMPLATE" ] && return
	mkdir -p "$TEMPLATE/data/touch"
	# touch/startup.cfg present, or CSQC init falls back to exec'ing battery.cfg
	echo "echo startup.cfg" > "$TEMPLATE/data/touch/startup.cfg"
	cat > "$TEMPLATE/data/bench-quit.cfg" <<'EOF'
alias _bq_0 "defer 0.05 _bq_poll"
alias _bq_1 "echo BENCH_DONE"
alias _bq_poll "_bq_$_touch_loadtime_menu"
_bq_poll
EOF
	# shellcheck disable=SC2046
	run_until "$OUT/template.log" BENCH_DONE 1 "$ENGINE" $(engine_args "$TEMPLATE") +exec bench-quit.cfg
	[ -f "$TEMPLATE/data/config.cfg" ] || { echo "template run made no config.cfg (see $OUT/template.log)" >&2; exit 1; }
	# a returning player: no first-run dialog over the main menu
	printf 'seta touch_profile_done 1\nseta _cl_name "Bench"\nseta cl_allow_uid2name 0\n' >> "$TEMPLATE/data/config.cfg"
}

fresh_userdir() { # name
	local u="$OUT/u-$1"
	rm -rf "$u"; cp -a "$TEMPLATE" "$u"
	echo "$u"
}

# job cfg: wait for the menu, idle MENU_IDLE s, run $1, wait for the game,
# leave the welcome dialog after 1 s and join; the harness stops after the
# engine's after_ready line, 5 s after the load
write_job() { # userdir command
	cat > "$1/data/bench-job.cfg" <<EOF
alias _bj_w0 "defer 0.05 _bj_wait"
alias _bj_w1 "defer $MENU_IDLE _bj_go"
alias _bj_wait "_bj_w\$_touch_loadtime_menu"
alias _bj_go "touch_loadtime_start; $2; _bj_gwait"
alias _bj_g0 "defer 0.05 _bj_gwait"
alias _bj_g1 "defer 1 _bj_play"
alias _bj_play "togglemenu 0; cmd join"
alias _bj_gwait "_bj_g\$_touch_loadtime_game"
_bj_wait
EOF
}

run_menu() { # run
	local u log; u=$(fresh_userdir "menu-$1"); log="$OUT/menu-$1.log"
	prepare_cache
	# shellcheck disable=SC2046
	run_until "$log" BENCH_DONE 1 "$ENGINE" $(engine_args "$u") +exec bench-quit.cfg || return 1
	echo "$log"
}

run_map() { # map run
	local u log; u=$(fresh_userdir "map-$1-$2"); log="$OUT/map-$1-$2.log"
	write_job "$u" "map $1"
	prepare_cache
	# shellcheck disable=SC2046
	run_until "$log" "LOADTIME after_ready" 1 "$ENGINE" $(engine_args "$u") +exec bench-job.cfg || return 1
	echo "$log"
}

run_join() { # map delay_ms run
	local map=$1 ms=$2 r=$3 mode=${4:-join} u su log slog port=26123
	u=$(fresh_userdir "$mode-$map-$ms-$r"); su="$OUT/u-${mode}sv-$map-$ms-$r"
	rm -rf "$su"; mkdir -p "$su"
	log="$OUT/$mode-$map-$ms-$r.log"; slog="$OUT/${mode}sv-$map-$ms-$r.log"
	write_job "$u" "connect 127.0.0.1:$port"
	if [ "$mode" = reconnect ]; then
		cat >> "$u/data/bench-job.cfg" <<EOF
alias _bj_play "togglemenu 0; cmd join; defer 1 _bj_disconnect"
alias _bj_disconnect "disconnect; defer 1 _bj_reconnect"
alias _bj_reconnect "touch_loadtime_start; connect 127.0.0.1:$port"
EOF
	elif [ "$mode" = vote ]; then
		cat >> "$u/data/bench-job.cfg" <<EOF
set rcon_password benchvote
set rcon_address 127.0.0.1:$port
alias _bj_play "togglemenu 0; cmd join; defer 2 _bv_go"
alias _bv_go "echo BENCH_VOTE_START; rcon endmatch; _bv_poll"
alias _bv_poll "touch_hud_state; defer 0.05 _bv_poll"
EOF
	fi
	prepare_cache
	# shellcheck disable=SC2016
	SVU="$su" unshare -rn bash -c '
		ip link set lo up
		if [ "$1" != 0 ] || [ -n "${LOSS:-}" ] || [ -n "${NETEM:-}" ]; then
			# shellcheck disable=SC2086
			tc qdisc add dev lo root netem delay "${1}ms" ${LOSS:+loss ${LOSS}%} ${NETEM:-}
		fi
		shift
		sv=$1; slog=$2; cl=$3; log=$4; map=$5; port=$6; mode=$7; shift 7
		# shellcheck disable=SC2086
		vote_args=()
		if [ "$mode" = vote ]; then
			vote_args=(+set rcon_password benchvote +set g_maplist "stormkeep boil glowplant finalrage silentsiege" +set g_maplist_votable 4)
		fi
		"$sv" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$SVU" +set port "$port" +set sv_public 0 "${vote_args[@]}" ${SV_EXTRA:-} +map "$map" > "$slog" 2>&1 &
		svpid=$!
		# the server takes a few seconds to load its map; the client only
		# connects once at its menu, and retries until the server answers
		for i in $(seq 1 200); do grep -q "Server spawned\|SpawnServer" "$slog" 2>/dev/null && break; sleep 0.05; done
		sleep 1
		status=0
		if [ "$mode" = vote ]; then
			run_vote_client "$log" "$cl" "$@" || status=$?
		else
			run_until "$log" "LOADTIME after_ready" 1 "$cl" "$@" || status=$?
		fi
		kill $svpid 2>/dev/null; wait $svpid 2>/dev/null || true
		exit "$status"
	' bash "$ms" "${SV_ENGINE:-$ENGINE}" "$slog" "$ENGINE" "$log" "$map" "$port" "$mode" $(engine_args "$u") +exec bench-job.cfg || return 1
	if [ "$mode" = reconnect ]; then
		# Retain the second load and its counters only.
		[ "$(grep -c 'LOADTIME game_ready' "$log" || true)" -ge 2 ] || { echo "reconnect did not complete: $log" >&2; return 1; }
		awk '/LOADTIME game_ready/ { n++ } n >= 2' "$log" > "$log.reconnect"
		log="$log.reconnect"
	fi
	echo "$log"
}

summarise() { # label logs...
	python3 - "$@" <<'EOF'
import re, sys, statistics
label, logs = sys.argv[1], sys.argv[2:]
rows = {}
order = []
def add(k, v):
    if k not in rows:
        rows[k] = []; order.append(k)
    rows[k].append(v)
for path in logs:
    stage = None
    for line in open(path, errors="replace"):
        if "LOADTIME" not in line:
            continue
        line = re.sub(r"\x1b\[[0-9;]*m", "", line).strip()
        m = re.match(r"LOADTIME (menu_ready|game_ready|world_preview|world_presented|after_ready|vote_ready)\s+(.*)", line)
        if m:
            stage = m.group(1)
            for k, v in re.findall(r"(\w+)=([-0-9.]+)", m.group(2)):
                add(f"{stage}.{k}", float(v))
            continue
        m = re.match(r"LOADTIME\s+signon (\d) at\s+([-0-9.]+) ms", line)
        if m and stage:
            add(f"{stage}.signon{m.group(1)}_ms", float(m.group(2))); continue
        m = re.match(r"LOADTIME\s+(.+?)\s+([-0-9.]+) (ms|MB)\s+(\d+) calls", line)
        if m and stage:
            add(f"{stage}.{m.group(1).strip()} [{m.group(3)}]", float(m.group(2))); continue
        m = re.match(r"LOADTIME\s+(.+?)\s+([-0-9.]+)( MB)?$", line)
        if m and stage:
            add(f"{stage}.{m.group(1).strip()}{' [MB]' if m.group(3) else ''}", float(m.group(2)))
print(f"== {label}: {len(logs)} runs")
for k in order:
    v = rows[k]
    med = statistics.median(v)
    print(f"  {k:<52} median {med:10.1f}   spread {min(v):9.1f} - {max(v):9.1f}   n={len(v)}")
EOF
}

make_template
for sc in "$@"; do
	logs=()
	case $sc in
		menu)
			for r in $(seq 1 "$RUNS"); do log=$(run_menu "$r") || exit 1; logs+=("$log"); done ;;
		cold-menu)
			for r in $(seq 1 "$RUNS"); do
				u="$OUT/u-cold-menu-$r"; rm -rf "$u"; mkdir -p "$u/data"
				cp "$TEMPLATE/data/bench-quit.cfg" "$u/data/"
				log="$OUT/cold-menu-$r.log"; prepare_cache
				# shellcheck disable=SC2046
				run_until "$log" BENCH_DONE 1 "$ENGINE" $(engine_args "$u") +exec bench-quit.cfg
				logs+=("$log")
			done ;;
		map:*)
			m=${sc#map:}
			for r in $(seq 1 "$RUNS"); do log=$(run_map "$m" "$r") || exit 1; logs+=("$log"); done ;;
		vote:*)
			m=${sc#vote:}
			export SVU="$OUT/u-joinsv"; mkdir -p "$SVU"
			for r in $(seq 1 "$RUNS"); do log=$(run_join "$m" 0 "$r" vote) || exit 1; logs+=("$log"); done ;;
		reconnect:*)
			m=${sc#reconnect:}
			export SVU="$OUT/u-joinsv"; mkdir -p "$SVU"
			for r in $(seq 1 "$RUNS"); do log=$(run_join "$m" 0 "$r" reconnect) || exit 1; logs+=("$log"); done ;;
		join:*)
			rest=${sc#join:}; m=${rest%%:*}; ms=0
			[ "$rest" != "$m" ] && ms=${rest#*:}
			export SVU="$OUT/u-joinsv"; mkdir -p "$SVU"
			for r in $(seq 1 "$RUNS"); do log=$(run_join "$m" "$ms" "$r") || exit 1; logs+=("$log"); done ;;
		*) echo "unknown scenario $sc" >&2; exit 1 ;;
	esac
	summarise "$sc ($CACHE cache)" "${logs[@]}" | tee -a "$OUT/summary.txt"
done
echo "logs and summary in $OUT"
