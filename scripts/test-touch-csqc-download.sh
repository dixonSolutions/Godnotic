#!/bin/bash
# A server running a csprogs.dat the client does not have: the public-server
# join path.
#
# Every public Xonotic server runs its own build; the client's bundled Touch
# CSQC is a different program (different CRC and size). cl_csqc_download 2
# means: keep the bundled one only when it is byte for byte the server's
# build, otherwise take the server's into dlcache/ and run that, with the
# engine's own touch overlay. This checks both halves of that:
#
#   1 the signon fetches the server's csprogs.dat, the join completes and the
#     server's program is the one running (TOUCHHUD ... csqc=1 ours=0);
#   2 a second join with the same userdir finds the copy in dlcache/ by name,
#     size and CRC: no second download, same join -- "the next join to that
#     server is instant".
#
# The client's "bundled" copy is the basedir's csprogs.dat plus one byte, in its
# userdir, so its CRC and size differ from what the server announces; the server
# serves the untouched file (a real program).
#
#   BASEDIR=<game dir with data/> ENGINE=<darkplaces-sdl> \
#       scripts/test-touch-csqc-download.sh [map]
#
# Exit 77: skipped (no engine or data). Exit 1: a check failed.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/engine/darkplaces/darkplaces-sdl}"
BASEDIR="${BASEDIR:-}"
MAP="${1:-stormkeep}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || { echo "SKIP: set ENGINE and BASEDIR"; exit 77; }
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
WORK="$(mktemp -d "${TMPDIR:-/tmp}/godnotic-csqc.XXXXXX")"
cleanup() {
	jobs -p | xargs -r kill 2> /dev/null
	if [ -n "${OUT:-}" ]; then mkdir -p "$OUT" && cp -r "$WORK"/. "$OUT/" 2> /dev/null; fi
	rm -rf "$WORK"
}
trap cleanup EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

port=$((26900 + RANDOM % 90))

# The server's own csprogs.dat, as fs.c finds it: the loose file or a pack.
server_csprogs() {
	local f
	[ -f "$BASEDIR/data/csprogs.dat" ] && { echo "$BASEDIR/data/csprogs.dat"; return 0; }
	for f in "$BASEDIR"/data/*.pk3; do
		[ -f "$f" ] || continue
		unzip -l "$f" > "$WORK/list.txt" 2> /dev/null || continue
		if grep -q " csprogs\.dat$" "$WORK/list.txt"; then echo "$f:csprogs.dat"; return 0; fi
	done
	return 1
}

src="$(server_csprogs)" || { echo "SKIP: no csprogs.dat in $BASEDIR/data"; exit 77; }
case "$src" in
*:csprogs.dat) unzip -p "${src%%:*}" csprogs.dat > "$WORK/srv-csprogs.dat" ;;
*)             cp "$src" "$WORK/srv-csprogs.dat" ;;
esac
[ -s "$WORK/srv-csprogs.dat" ] || { echo "FAIL: could not read the basedir's csprogs.dat"; exit 1; }

"$ENGINE" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$WORK/sv" \
	+set port "$port" +set sv_public 0 +set sv_allowdownloads 1 \
	+set bot_number 1 +set g_maplist "$MAP" +set g_warmup 0 +set g_dm 1 \
	+map "$MAP" > "$WORK/server.log" 2>&1 &
for _ in $(seq 1 900); do
	grep -q "Server spawned" "$WORK/server.log" 2> /dev/null && break
	sleep 0.1
done
if ! grep -q "Server spawned" "$WORK/server.log"; then
	echo "FAIL: the dedicated server did not start" >&2
	tail -20 "$WORK/server.log" >&2
	exit 1
fi
sleep 1

d="$WORK/cl"
mkdir -p "$d/data"
cp "$WORK/srv-csprogs.dat" "$d/data/csprogs.dat"
printf 'x' >> "$d/data/csprogs.dat"
{
	echo "seta touch_profile_done 1"
	echo "seta _cl_name csqctest"
	echo "seta cl_allow_uid2name 0"
	echo "seta cl_csqc_download 2"
} > "$d/data/autoexec.cfg"
{
	echo "connect 127.0.0.1:$port"
	for n in $(seq 1 40); do echo "defer $n \"touch_hud_state\""; done
	echo 'defer 45 "quit"'
} > "$d/data/join.cfg"

join() { # logfile
	timeout 150 "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$d" \
		+vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 \
		+set developer 1 +exec join.cfg > "$1" 2>&1
}

join "$WORK/first.log"
log="$WORK/first.log"
grep -aq "Touch CSQC differs from the server's build" "$log" && ok "the client's csprogs.dat differs from the server's" || bad "the local file was not the differing one (log: $log)"
grep -aq 'Downloaded "dlcache/csprogs\.dat\.' "$log" && ok "the server's csprogs.dat was downloaded into dlcache" || bad "no dlcache copy of the server's csprogs.dat (log: $log)"
grep -aq "csprogs.dat is not the same version as the server" "$log" && bad "disconnected on the csprogs CRC: $(grep -a -m1 'csprogs.dat is not the same version' "$log")" || ok "no csprogs CRC disconnect"
grep -aq "TOUCHHUD .*csqc=1 ours=0" "$log" && ok "the server's CSQC is the running program" || bad "the server's CSQC is not the running program (log: $log)"
grep -aq "TOUCHHUD mode=spectating" "$log" && ok "the client is in the game (spectating)" || bad "the client never got into the game (log: $log)"

join "$WORK/second.log"
log="$WORK/second.log"
grep -aq "Downloading new CSQC code" "$log" && bad "the second join downloaded the server's csprogs.dat again (dlcache/ copy not reused)" || ok "the second join reused the dlcache copy (no download)"
grep -aq "TOUCHHUD .*csqc=1 ours=0" "$log" && ok "second join: the server's CSQC is the running program" || bad "second join: the server's CSQC is not the running program (log: $log)"
grep -aq "TOUCHHUD mode=spectating" "$log" && ok "second join: the client is in the game (spectating)" || bad "second join: the client never got into the game (log: $log)"

[ $fail = 0 ] && echo "csqc download: all checks passed"
exit $fail
