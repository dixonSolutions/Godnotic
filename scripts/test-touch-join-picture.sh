#!/usr/bin/env bash
# A server join never shows the logo screen: from the first connect snapshot the
# native card has a picture file. With the map unknown (nothing answers yet) it is a
# generic map picture marked "generic" (the card dims and blurs it); once the server
# names a map this install has, it is that map's own picture ("map"). The snapshot
# also carries the precache step and the download size fields the card shows.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE=${ENGINE:-$ROOT/build/bin/xonotic}
: "${BASEDIR:?set BASEDIR to a game directory with data/}"
[ -x "$ENGINE" ] && [ -d "$BASEDIR/data" ] || exit 77
work=$(mktemp -d)
trap 'jobs -p | xargs -r kill 2> /dev/null; rm -rf "$work"' EXIT
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy
unset DISPLAY WAYLAND_DISPLAY
fail=0
bad() { echo "FAIL: $*" >&2; fail=1; }
client() { # dir address seconds
	mkdir -p "$1/data"
	cat > "$1/data/jp.cfg" <<CFG
set touch_profile_done 1
set _cl_name JoinPictureTest
set vid_touchscreen 1
set fe_echo 1
fe attach 1
connect $2
defer $3 quit
CFG
	timeout $(($3 + 60)) "$ENGINE" -xonotic -basedir "$BASEDIR" -userdir "$1" \
		+vid_fullscreen 0 +vid_width 640 +vid_height 360 +set _touch_assets_ready 1 \
		+exec jp.cfg > "$1/log" 2>&1 || true
}

# 1. nobody answers: the map is unknown, a generic picture still shows
client "$work/c1" 127.0.0.1:26999 8
line=$(grep -a '^FE> connect .*"stage":"connecting"' "$work/c1/log" | head -1 || true)
[ -n "$line" ] || { tail -20 "$work/c1/log" >&2; bad "no connecting snapshot"; }
case "$line" in *'"levelshot":"/'*) ;; *) bad "no picture file while connecting to an unknown server: $line" ;; esac
case "$line" in *'"levelshot_kind":"generic"'*) ;; *) bad "the picture of an unknown map is not marked generic: $line" ;; esac
case "$line" in *'"precache":""'*'"levelshot_kind"'*) ;; *) bad "no precache fields: $line" ;; esac

# 2. a server with a map this install has: that map's own picture
port=26998
mkdir -p "$work/sv/data"
"$ENGINE" -dedicated -xonotic -basedir "$BASEDIR" -userdir "$work/sv" +set port $port +set sv_public 0 \
	+set g_maplist stormkeep +set bot_number 0 +set minplayers 0 +map stormkeep > "$work/sv.log" 2>&1 &
for _ in $(seq 1 900); do grep -q "Server spawned" "$work/sv.log" 2> /dev/null && break; sleep 0.1; done
client "$work/c2" 127.0.0.1:$port 12
line=$(grep -a '^FE> connect .*"levelshot_kind":"map"' "$work/c2/log" | head -1 || true)
if [ -z "$line" ]; then
	grep -a '^FE> connect' "$work/c2/log" | head -5 >&2
	bad "no snapshot with the map's own picture"
else
	case "$line" in *'"levelshot":"/'*stormkeep.jpg'"'*) ;; *) bad "wrong picture for stormkeep: $line" ;; esac
fi
# never a snapshot of the join without a picture before the world draws
if grep -a '^FE> connect ' "$work/c2/log" | grep -a '"stage":"\(connecting\|signon\|loading\|downloading\)"' | grep -aq '"levelshot":""'; then
	bad "a join snapshot had no picture: $(grep -a '^FE> connect ' "$work/c2/log" | grep -a '"levelshot":""' | grep -a '"stage":"\(connecting\|signon\|loading\|downloading\)"' | head -1)"
fi
[ $fail = 0 ] && echo "join picture: all checks passed"
exit $fail
