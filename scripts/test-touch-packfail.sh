#!/bin/bash
# A map pack that cannot be downloaded must stop the join with a clear card
# ("Join failed (files)": what and why, Retry and Back), never crash or hang
# the client, and leave no half file that breaks the next start.
#
# A local dedicated server needs two packs over HTTP. Each case breaks the
# download a different way (tests/net/fault_http.py) or the client's side of it:
#
#   notfound   the download URL answers 404
#   http503    the download URL answers 503 for ever
#   drop       the server closes the connection mid-file, every time
#   stall      the server stops sending mid-file (a hung connection)
#   notzip     the file arrives whole but is not a pack (it cannot be mounted)
#   badcrc     the pack is intact but not what the server announced (curl_expect)
#   diskfull   the download folder is a 256 KB tmpfs
#   onebad     two packs, the first is fine, the second is a 404
#   cancel     the player leaves (disconnect) in the middle of the download
#   mapchange  the server changes map in the middle of the download
#   restart    after all of that, the client starts again on the same files
#
# Each case checks: the client is alive and answering to the end (a probe prints
# every second), it did not crash, the card text is the one expected, and no
# invalid pack is left in dlcache/.
#
#   BASEDIR=<game dir with data/> ENGINE=<xonotic> SERVER=<darkplaces-dedicated> scripts/test-touch-packfail.sh [-o OUTDIR] [case...]
#
# Exit 77: skipped. 1: a check failed.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
SERVER="${SERVER:-$ROOT/engine/darkplaces/darkplaces-dedicated}"
BASEDIR="${BASEDIR:-}"
OUT=""
while getopts "o:" o; do case $o in o) OUT=$OPTARG ;; *) exit 1 ;; esac; done
shift $((OPTIND - 1))
CASES="${*:-notfound http503 drop stall notzip badcrc diskfull onebad cancel mapchange restart}"
[ -x "$ENGINE" ] && [ -x "$SERVER" ] && [ -d "$BASEDIR/data" ] || { echo "SKIP: set ENGINE, SERVER and BASEDIR"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-packfail.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	[ -n "$OUT" ] && { mkdir -p "$OUT"; cp "$WORK"/*.log "$OUT/" 2> /dev/null; }
	rm -rf "$WORK"
}
trap cleanup EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

MAP=afterslime; MAP2=atelier
port=$((26900 + RANDOM % 90)); hp=$((8400 + RANDOM % 90))
mkdir -p "$WORK/www" "$WORK/sv/data"
python3 - "$WORK/www" <<'EOF'
import os, sys, zipfile
for n, mb in (("zzpackfail1", 3), ("zzpackfail2", 3)):
    with zipfile.ZipFile(os.path.join(sys.argv[1], n + ".pk3"), "w", zipfile.ZIP_STORED) as z:
        z.writestr("packfail/%s.bin" % n, os.urandom(mb * 1048576))
EOF
cp "$WORK"/www/*.pk3 "$WORK/sv/data/"
size1=$(stat -c %s "$WORK/www/zzpackfail1.pk3")

src="$BASEDIR/data/zz-xonotic-data.pk3dir/csprogs.dat"
[ -f "$src" ] || { echo "SKIP: no csprogs.dat in $BASEDIR"; exit 77; }

mkfifo "$WORK/sv.in"; exec 7<> "$WORK/sv.in"
"$SERVER" -xonotic -basedir "$BASEDIR" -userdir "$WORK/sv" \
	+set port "$port" +set sv_public 0 +set sv_allowdownloads 1 \
	+set sv_curl_defaulturl "http://127.0.0.1:$hp/" +set sv_curl_serverpackages "packfail/zzpackfail1.bin packfail/zzpackfail2.bin" \
	+set bot_number 0 +set g_maplist "$MAP $MAP2" +set g_warmup 0 +set g_dm 1 \
	+map "$MAP" <&7 > "$WORK/server.log" 2>&1 &
for _ in $(seq 1 900); do grep -q "Server spawned" "$WORK/server.log" 2> /dev/null && break; sleep 0.1; done
grep -q "Server spawned" "$WORK/server.log" || { echo "FAIL: the dedicated server did not start"; tail -20 "$WORK/server.log"; exit 1; }
sleep 1

HTTP=""
http() { # dir mode [kbps]
	http_stop
	if [ "$2" = slow ]; then python3 "$ROOT/tests/net/range_http.py" "$1" "$hp" "${3:-200}" 2> "$WORK/http.log" &
	else python3 "$ROOT/tests/net/fault_http.py" "$1" "$hp" "$2" 2> "$WORK/http.log" &
	fi
	HTTP=$!; sleep 0.5
}
http_stop() { [ -n "$HTTP" ] && kill "$HTTP" 2> /dev/null && wait "$HTTP" 2> /dev/null; HTTP=""; }

# run NAME SECONDS "client cfg" [wrapper...]: joins, then lets the client run SECONDS and checks it
# sets LOG, CL, RC (the client's exit status, 0 = still running when we stopped it)
run() {
	local name=$1 secs=$2 extra=$3 pid n
	shift 3
	CL="${CLDIR:-$WORK/cl-$name}"; LOG="$WORK/$name.log"
	mkdir -p "$CL/data/dlcache"
	[ -f "$CL/data/csprogs.dat" ] || { cp "$src" "$CL/data/csprogs.dat"; }
	{
		echo "seta touch_profile_done 1"; echo "seta _cl_name packfail"; echo "seta cl_allow_uid2name 0"
		echo "seta curl_segments 1"; echo "seta curl_retries 1"; echo "seta curl_mirrors \"\""
		echo "$extra"
	} > "$CL/data/autoexec.cfg"
	{
		echo "connect 127.0.0.1:$port"
		for n in $(seq 1 "$secs"); do echo "defer $n \"echo PROBE_$n\""; done
		echo "defer $((secs + 1)) \"quit\""
	} > "$CL/data/join.cfg"
	"$@" "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$CL" +vid_fullscreen 0 +vid_width 640 +vid_height 360 \
		+set _touch_assets_ready 1 +set developer 1 +exec join.cfg > "$LOG" 2>&1 &
	pid=$!
	for n in $(seq 1 $(((secs + 20) * 10))); do kill -0 $pid 2> /dev/null || break; sleep 0.1; done
	if kill -0 $pid 2> /dev/null; then kill $pid 2> /dev/null; wait $pid 2> /dev/null; RC=hung; else wait $pid; RC=$?; fi
}

healthy() { # NAME SECONDS: the client ran to the end by itself, no crash, the probe kept going
	local name=$1 secs=$2
	[ "$RC" = hung ] && { bad "$name: the client froze (did not reach its own quit; $LOG)"; return; }
	grep -aq "Engine Crash\|Segmentation\|Host_Error: .*download\|corrupt download" "$LOG" && bad "$name: crash or download Host_Error in $LOG"
	grep -aq "PROBE_$secs" "$LOG" && ok "$name: the client answered to the end (no crash, no freeze)" || bad "$name: the probe stopped early ($LOG)"
	[ "$RC" = 0 ] || bad "$name: the client exited with $RC ($LOG)"
}
card() { # NAME PATTERN
	grep -a "Join failed (files)" "$LOG" | grep -aq "$2" && ok "$1: the card says: $(grep -a -m1 'Join failed (files)' "$LOG" | sed 's/.*Join failed (files): //' | cut -c1-150)" || bad "$1: no files card matching '$2' ($LOG)"
}
no_badpack() { # NAME: a pack that is complete but not a whole zip would be mounted and break; a partial one is kept for resuming
	local f badone="" part=""
	for f in "$CL"/data/dlcache/*.pk3; do
		[ -s "$f" ] || continue
		if python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1]).testzip()" "$f" 2> /dev/null; then continue; fi
		if [ "$(stat -c %s "$f")" -ge "$size1" ]; then badone="$badone $f"; else part="$part $(basename "$f")"; fi
	done
	[ -z "$badone" ] && ok "$1: no complete-but-broken pack left in dlcache/${part:+ (partial kept for resume:$part)}" || bad "$1: broken complete pack(s) left:$badone"
}

for c in $CASES; do
	echo "--- $c"
	case $c in
	notfound) http "$WORK/www" notfound; run $c 14 ""; healthy $c 14; card $c "404"; no_badpack $c ;;
	http503)  http "$WORK/www" error:503; run $c 20 ""; healthy $c 20; card $c "503"; no_badpack $c ;;
	drop)     http "$WORK/www" drop:300000; run $c 20 ""; healthy $c 20; card $c "dropping\|zzpackfail"; no_badpack $c ;;
	stall)    http "$WORK/www" stall:300000; run $c 70 "seta curl_retries 0"; healthy $c 70; card $c "stalled"; no_badpack $c ;;
	notzip)   http "$WORK/www" garbage; run $c 16 ""; healthy $c 16; card $c "damaged"; no_badpack $c ;;
	badcrc)   http "$WORK/www" ok; run $c 16 "curl_expect zzpackfail1.pk3 $size1 deadbeef"; healthy $c 16; card $c "damaged"; no_badpack $c ;;
	diskfull)
		unshare -rm true 2> /dev/null || { echo "skip $c: no user namespaces"; continue; }
		http "$WORK/www" ok
		mkdir -p "$WORK/cl-$c/data/dlcache"
		run $c 16 "" unshare -rm bash -c "mount -t tmpfs -o size=256k tmpfs '$WORK/cl-$c/data/dlcache' && exec \"\$@\"" _
		healthy $c 16; card $c "storage"; no_badpack $c ;;
	onebad)   mkdir -p "$WORK/www1"; cp "$WORK/www/zzpackfail1.pk3" "$WORK/www1/"; http "$WORK/www1" ok; run $c 16 ""; healthy $c 16; card $c "zzpackfail2"; no_badpack $c ;;
	cancel)   http "$WORK/www" slow 200; run $c 14 'defer 4 "disconnect"'; healthy $c 14
		grep -aq "Disconnected" "$LOG" && ok "$c: the player left in the middle of the download"
		awk '/Download of dlcache\/zzpackfail1.pk3: ABORTED/{a=NR} /PROBE_7/{p=NR} END{exit !(a && p && a < p)}' "$LOG" && ok "$c: the download stopped at the disconnect" || bad "$c: the download kept running after the disconnect ($LOG)" || bad "$c: no disconnect ($LOG)"
		grep -a "Join failed" "$LOG" | grep -aq . && bad "$c: the player's own cancel showed a failure card" || ok "$c: no failure card for the player's own cancel"
		[ "$(grep -ac "Downloading http://127" "$LOG")" -le 2 ] && ! grep -aq "Download of .* failed" "$LOG" && ok "$c: nothing restarted after the cancel" || bad "$c: the download restarted ($LOG)"
		no_badpack $c ;;
	mapchange)
		http "$WORK/www" slow 200
		( sleep 8; echo "map $MAP2" >&7 ) &
		run $c 22 ""; healthy $c 22; no_badpack $c ;;
	restart)
		# the leftovers of the drop case (a partial pack, a resume record) and of the badcrc case
		# on a server that works now: the next start must not trip over them, and Retry must succeed
		http "$WORK/www" ok
		for left in drop badcrc notzip; do
			[ -d "$WORK/cl-$left" ] || continue
			CLDIR="$WORK/cl-$left" run restart-$left 16 "seta curl_retries 3"; unset CLDIR
			healthy restart-$left 16
			grep -a "Join failed" "$LOG" | grep -aq . && bad "restart-$left: failed again on a working server ($LOG)" || ok "restart-$left: joined on a working server"
			[ "$(sha256sum "$CL/data/dlcache/zzpackfail1.pk3" 2> /dev/null | cut -d' ' -f1)" = "$(sha256sum "$WORK/www/zzpackfail1.pk3" | cut -d' ' -f1)" ] && ok "restart-$left: the pack is whole" || bad "restart-$left: the pack is missing or wrong ($LOG)"
		done ;;
	esac
done
http_stop

[ $fail = 0 ] && echo "packfail: all checks passed"
exit $fail
