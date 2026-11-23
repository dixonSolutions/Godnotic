#!/bin/bash
# Faster join downloads (docs/NETWORK.md, "Join downloads"): a local dedicated
# server that needs a map pack over HTTP and a csprogs.dat the client lacks.
#
#   overlap  the csprogs.dat download (game connection) runs beside the pack
#            (HTTP) instead of after it: join time with net_download_overlap 0
#            (stock order) and 1, same server, same files
#   mirror   the server's URL answers 404: the pack is taken from curl_mirrors
#            by its exact file name, and a curl_expect crc that does not match
#            rejects it
#   dead     the server's URL is down and there is no mirror: the join stops
#            with the "Could not download" card in a bounded time
#
#   BASEDIR=<game dir with data/> ENGINE=<xonotic> scripts/test-touch-dlfast.sh [-s PACK_MB] [-k KBPS]
#
# Prints one "| case | seconds |" line each. Exit 77: skipped. 1: a check failed.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
SERVER="${SERVER:-$ROOT/engine/darkplaces/darkplaces-dedicated}" # make sv-release
BASEDIR="${BASEDIR:-}"
MB=6
KBPS=700
while getopts "s:k:" o; do case $o in s) MB=$OPTARG ;; k) KBPS=$OPTARG ;; *) exit 1 ;; esac; done
[ -x "$ENGINE" ] && [ -x "$SERVER" ] && [ -d "$BASEDIR/data" ] || { echo "SKIP: set ENGINE, SERVER (make sv-release) and BASEDIR"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-dlfast.XXXXXX")"
trap 'jobs -p | xargs -r kill 2> /dev/null; rm -rf "$WORK"' EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

MAP=afterslime
port=$((26900 + RANDOM % 90))
hp=$((8200 + RANDOM % 90))      # the server's own pack URL
mp=$((8300 + RANDOM % 90))      # a mirror
mkdir -p "$WORK/www" "$WORK/mir" "$WORK/empty" "$WORK/sv/data"
python3 - "$WORK/www/zzdlfast.pk3" "$MB" <<'EOF'
import os, sys, zipfile
path, mb = sys.argv[1], float(sys.argv[2])
with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
    z.writestr("dlfast/blob000.bin", os.urandom(int(mb * 1048576)))
EOF
cp "$WORK/www/zzdlfast.pk3" "$WORK/sv/data/"
cp "$WORK/www/zzdlfast.pk3" "$WORK/mir/"
want=$(sha256sum "$WORK/www/zzdlfast.pk3" | cut -d' ' -f1)
size=$(stat -c %s "$WORK/www/zzdlfast.pk3")
crc=$(python3 -c "import zlib,sys;print('%08x'%(zlib.crc32(open(sys.argv[1],'rb').read())&0xffffffff))" "$WORK/www/zzdlfast.pk3")

start_http() { # dir port kbps
	python3 "$ROOT/tests/net/range_http.py" "$1" "$2" "$3" 2> "$WORK/http-$2.log" &
	echo $! > "$WORK/http-$2.pid"
}
stop_http() { [ -f "$WORK/http-$1.pid" ] && kill "$(cat "$WORK/http-$1.pid")" 2> /dev/null; rm -f "$WORK/http-$1.pid"; }

# the server's csprogs.dat is the basedir's, the client's differs by one byte
src="$BASEDIR/data/zz-xonotic-data.pk3dir/csprogs.dat"
[ -f "$src" ] || src="$(find -L "$BASEDIR/data" -maxdepth 2 -name csprogs.dat | head -1)"
[ -n "$src" ] && [ -f "$src" ] || { echo "SKIP: no csprogs.dat in $BASEDIR"; exit 77; }

"$SERVER" -xonotic -basedir "$BASEDIR" -userdir "$WORK/sv" \
	+set port "$port" +set sv_public 0 +set sv_allowdownloads 1 \
	+set sv_curl_defaulturl "http://127.0.0.1:$hp/" +set sv_curl_serverpackages "dlfast/blob000.bin" \
	+set bot_number 0 +set g_maplist "$MAP" +set g_warmup 0 +set g_dm 1 \
	+map "$MAP" > "$WORK/server.log" 2>&1 &
for _ in $(seq 1 900); do grep -q "Server spawned" "$WORK/server.log" 2> /dev/null && break; sleep 0.1; done
grep -q "Server spawned" "$WORK/server.log" || { echo "FAIL: the dedicated server did not start"; tail -20 "$WORK/server.log"; exit 1; }
sleep 1

# join NAME "extra client cfg" -> sets T (seconds to being in the game, or FAIL) and CL (the client's userdir)
join() {
	local name=$1 extra=$2 t0 t1 pid n
	CL="$WORK/cl-$name"; LOG="$WORK/$name.log"
	rm -rf "$CL"; mkdir -p "$CL/data"
	cp "$src" "$CL/data/csprogs.dat"; printf 'x' >> "$CL/data/csprogs.dat"
	{
		echo "seta touch_profile_done 1"
		echo "seta _cl_name dlfast"
		echo "seta cl_allow_uid2name 0"
		echo "seta cl_csqc_download 2"
		echo "seta curl_segments 1"
		echo "$extra"
	} > "$CL/data/autoexec.cfg"
	{
		echo "connect 127.0.0.1:$port"
		for n in $(seq 1 600); do echo "defer $n \"touch_hud_state\""; done
	} > "$CL/data/join.cfg"
	t0=$(date +%s.%N)
	"$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$CL" +vid_fullscreen 0 +vid_width 640 +vid_height 360 \
		+set _touch_assets_ready 1 +set developer 1 +exec join.cfg > "$LOG" 2>&1 &
	pid=$!
	T=FAIL
	for n in $(seq 1 1800); do # 90 s
		if grep -aq "TOUCHHUD mode=spectating" "$LOG" 2> /dev/null; then
			t1=$(date +%s.%N)
			T=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b - a}')
			break
		fi
		kill -0 $pid 2> /dev/null || break
		sleep 0.05
	done
	kill $pid 2> /dev/null; wait $pid 2> /dev/null
}

have_pack() { [ "$(sha256sum "$CL/data/dlcache/zzdlfast.pk3" 2> /dev/null | cut -d' ' -f1)" = "$want" ]; }

echo "| case | seconds |"
echo "|---|---|"

# 1 overlap: the pack comes at KBPS per connection, csprogs.dat over the game connection
start_http "$WORK/www" "$hp" "$KBPS"; sleep 0.5
join seq "seta net_download_overlap 0"
echo "| pack $MB MB at $KBPS KB/s + csprogs.dat, stock order | $T |"; seq_t=$T
have_pack && ok "stock order: pack arrived intact" || bad "stock order: pack missing or damaged ($LOG)"
grep -aq 'Downloaded "dlcache/csprogs\.dat\.' "$LOG" && ok "stock order: csprogs.dat downloaded" || bad "stock order: no csprogs.dat download ($LOG)"
join ovl "seta net_download_overlap 1"
echo "| same, overlapped (net_download_overlap 1) | $T |"; ovl_t=$T
have_pack && ok "overlap: pack arrived intact" || bad "overlap: pack missing or damaged ($LOG)"
grep -aq 'Downloaded "dlcache/csprogs\.dat\.' "$LOG" && ok "overlap: csprogs.dat downloaded" || bad "overlap: no csprogs.dat download ($LOG)"
grep -aq "TOUCHHUD .*csqc=1 ours=0" "$LOG" && ok "overlap: the server's CSQC is the running program" || bad "overlap: the server's CSQC is not running ($LOG)"
grep -aq "csprogs.dat is not the same version" "$LOG" && bad "overlap: disconnected on the csprogs check" || ok "overlap: no csprogs CRC disconnect"
if [ "$seq_t" != FAIL ] && [ "$ovl_t" != FAIL ] && awk -v a="$seq_t" -v b="$ovl_t" 'BEGIN{exit !(b < a - 1.0)}'; then
	ok "overlap: join $ovl_t s against $seq_t s in stock order"
else
	bad "overlap: not faster ($ovl_t s against $seq_t s)"
fi
# a second join keeps both files: nothing to download
join again "seta net_download_overlap 1"
cp -r "$CL/data/dlcache" "$WORK/dl-keep" 2> /dev/null
stop_http "$hp"

# 2 mirror: the server's URL has no such file, the mirror has it by name
start_http "$WORK/empty" "$hp" 0; start_http "$WORK/mir" "$mp" 0; sleep 0.5
join nomirror "seta curl_retries 1"
echo "| server URL 404, no mirror | $T |"
grep -a "Join failed (files)" "$LOG" | grep -aq "404" && ok "no mirror: the join stops with the card: $(grep -a -m1 'Join failed (files)' "$LOG" | sed 's/.*(files): //' | cut -c1-110)" || bad "no mirror: no 'Could not download' card ($LOG)"
have_pack && bad "no mirror: a pack appeared from nowhere" || ok "no mirror: no pack, as expected"
join mirror "seta curl_mirrors http://127.0.0.1:$mp/
curl_expect zzdlfast.pk3 $size $crc"
echo "| server URL 404, mirror by file name, size+crc checked | $T |"
[ "$T" != FAIL ] && ok "mirror: joined" || bad "mirror: the join did not complete ($LOG)"
have_pack && ok "mirror: the pack came from the mirror, intact" || bad "mirror: pack missing or damaged ($LOG)"
grep -aq "trying the mirror" "$LOG" && ok "mirror: the log names the mirror" || bad "mirror: no mirror attempt logged ($LOG)"
join badcrc "seta curl_mirrors http://127.0.0.1:$mp/
curl_expect zzdlfast.pk3 $size deadbeef
seta curl_retries 1"
echo "| mirror serves a file with the wrong crc | $T |"
have_pack && bad "badcrc: the wrong file was kept" || ok "badcrc: the wrong file was rejected"
grep -aq "not the crc the server announced" "$LOG" && ok "badcrc: the reason is logged" || bad "badcrc: no reason logged ($LOG)"
join badsize "seta curl_mirrors http://127.0.0.1:$mp/
curl_expect zzdlfast.pk3 $((size + 1)) $crc
seta curl_retries 1"
have_pack && bad "badsize: the wrong file was kept" || ok "badsize: the wrong size was rejected"
stop_http "$hp"; stop_http "$mp"

# 3 dead: nothing listens at the server's URL, no mirror
join dead "seta curl_retries 1"
echo "| server URL down, no mirror | $T |"
grep -a "Join failed (files)" "$LOG" | grep -aq "Could not download" && ok "dead: the join stops with the card: $(grep -a -m1 'Join failed (files)' "$LOG" | sed 's/.*(files): //' | cut -c1-110)" || bad "dead: no 'Could not download' card ($LOG)"

[ $fail = 0 ] && echo "dlfast: all checks passed"
exit $fail
