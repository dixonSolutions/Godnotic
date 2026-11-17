#!/bin/bash
# Frame-time benchmark of a real local match: `map M` with bots, a fixed
# number of seconds played, then the frame-time summary the engine prints at
# shutdown (engine/darkplaces/touch_frametime.h, one FRAMETIME line) and the
# host_speeds server/gfx/snd split of the same run.
#
#   BASEDIR=/path/to/basedir scripts/bench-frames.sh [-n RUNS] [-s SECONDS] [-o OUTDIR] [-e "extra +args"] MAP...
#
# BASEDIR is a game dir holding data/ (the pk3s plus this checkout's
# xonotic-data.pk3dir, xonotic.cfg and touch/; scripts/test-data-split.sh
# make_base shows the layout). ENGINE defaults to build/bin/xonotic.
#
# Every run gets a fresh userdir, a DM match with BOTS bots, warmup off, and
# quits SECONDS after the game starts, so the load, the menu and the first
# frames are not in the numbers: only in-game frames are (the recorder keeps
# none before the client is fully signed on).
#
#   W, H        window size (default 1280x720, offscreen SDL)
#   BOTS        bots in the match (default 6)
#   LIBS        extra LD_LIBRARY_PATH
#
# Prints one row per run and a median-of-runs line per map; the qconsole.log
# of every run stays in OUTDIR.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNS=3
SECONDS_PER_MAP=30
OUT=""
EXTRA=""
while getopts "n:s:o:e:h" opt; do
	case $opt in
		n) RUNS=$OPTARG ;;
		s) SECONDS_PER_MAP=$OPTARG ;;
		o) OUT=$OPTARG ;;
		e) EXTRA=$OPTARG ;;
		*) sed -n '2,24p' "$0"; exit 1 ;;
	esac
done
shift $((OPTIND - 1))
[ $# -gt 0 ] || { sed -n '2,24p' "$0"; exit 1; }
: "${BASEDIR:?set BASEDIR to a game dir holding data/}"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
W="${W:-1280}"; H="${H:-720}"; BOTS="${BOTS:-6}"
OUT="${OUT:-$(mktemp -d "${TMPDIR:-/tmp}/bench-frames.XXXXXX")}"
mkdir -p "$OUT"
export LD_LIBRARY_PATH="${LIBS:+$LIBS:}${LD_LIBRARY_PATH:-}"
export SDL_VIDEODRIVER=offscreen SDL_AUDIODRIVER=dummy

engine_args() { # userdir map
	# shellcheck disable=SC2086
	echo -xonotic -basedir "$BASEDIR" -userdir "$1" +exec xonotic.cfg \
		+vid_fullscreen 0 +vid_width "$W" +vid_height "$H" +vid_touchscreen 0 \
		+set _touch_assets_ready 1 +set touch_profile_done 1 +set cl_allow_uid2name 0 \
		+set touch_loadtime 1 +host_speeds "${SPEEDS:-0}" +r_speeds 0 +sv_cheats 1 \
		+gametype dm +bot_number "$BOTS" +minplayers "$((BOTS + 1))" +skill 6 +g_warmup 0 \
		+map "$2" +alias "cl_hook_gamestart_all" "defer $SECONDS_PER_MAP quit" $EXTRA
}

run_one() { # map run
	local m=$1 r=$2 u log
	u="$OUT/u-$m-$r"
	rm -rf "$u"; mkdir -p "$u/data/touch"
	echo "echo startup.cfg" > "$u/data/touch/startup.cfg"
	log="$OUT/$m-$r.log"
	# the engine exits by itself (defer quit); give it the load plus the run
	# shellcheck disable=SC2046
	timeout $((SECONDS_PER_MAP + 180)) "$ENGINE" $(engine_args "$u" "$m") > "$log" 2>&1 || true
	echo "$log"
}

LOGS=()
for m in "$@"; do
	for r in $(seq 1 "$RUNS"); do
		echo "run $m $r/$RUNS" >&2
		LOGS+=("$(run_one "$m" "$r")")
	done
done

python3 - "$OUT" "${LOGS[@]}" <<'EOF'
import os, re, statistics, sys
out, logs = sys.argv[1], sys.argv[2:]
frames = re.compile(r"FRAMETIME map=(\S+) frames=(\d+) median=([\d.]+) p95=([\d.]+) p99=([\d.]+) hitches33=(\d+) \(([\d.]+)%\) max=([\d.]+) mean=([\d.]+)")
speeds = re.compile(r"\s*(\d+)us total\s+(\d+)us server\s+(\d+)us gfx\s+(\d+)us snd")
rows, sub = [], {}
for path in logs:
    for line in open(path, errors="replace"):
        line = re.sub(r"\^[0-9]", "", re.sub(r"\x1b\[[0-9;]*m", "", line))
        if "FRAMETIME" in line:
            m = frames.search(line)
            if m:
                rows.append(dict(map=m.group(1), frames=int(m.group(2)), median=float(m.group(3)),
                                 p95=float(m.group(4)), p99=float(m.group(5)), hitches=int(m.group(6)),
                                 hitchpct=float(m.group(7)), max=float(m.group(8)), mean=float(m.group(9))))
        else:
            m = speeds.match(line)
            if m:
                s = sub.setdefault(os.path.basename(path), [[], [], [], []])
                for i in range(4):
                    s[i].append(int(m.group(i + 1)) / 1000.0)
print("%-14s %6s %8s %8s %8s %9s %9s %8s %9s" %
      ("map", "frames", "median", "p95", "p99", "hitch>33", "%", "max", "mean"))
for r in rows:
    print("%-14s %6d %7.2f %7.2f %7.2f %9d %8.1f%% %7.2f %8.2f" %
          (r["map"], r["frames"], r["median"], r["p95"], r["p99"], r["hitches"], r["hitchpct"], r["max"], r["mean"]))
if rows:
    print()
    for key in ("frames", "median", "p95", "p99", "hitchpct", "max", "mean"):
        print("  median of runs %-9s %8.2f" % (key, statistics.median(r[key] for r in rows)))
    print("  median of runs hitches33 %8.2f" % statistics.median(r["hitches"] for r in rows))
if sub:
    print("\nhost_speeds, median ms per frame (total/server/gfx/snd):")
    for name in sorted(sub):
        v = sub[name]
        print("  %-28s %6.2f %6.2f %6.2f %6.2f  (%d frames)" %
              (name, *(statistics.median(x) for x in v), len(v[0])))
EOF
echo "logs in $OUT"
