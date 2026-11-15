#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The GTK front end keeps the player's server list sort (docs/UI_BRIDGE.md, "Lists that
# stay put while they update"): the loader with the real engine on a private Xvfb, against
# 200 fake servers on loopback (tests/srvlist/fakenet.py), driven by its script runner
# (loader/crates/gtk/src/script.rs). A click on Players at the top sorts by players, most
# first; a Refresh streaming the servers in, Back and Multiplayer again, and a restart of
# the app keep it, and the list stays where it was scrolled while the rows update.
#
#   [ENGINE=build/bin/xonotic] [GTK_BASEDIR=<basedir the Flatpak sandbox can read>] \
#   [GODNOTIC_LOADER_BIN=loader/target/release/godnotic-loader] [OUT=<keep the run here>] scripts/test-gtk-mpsort.sh
#
# GTK_BASEDIR defaults to BASEDIR; its pk3s must be readable inside the sandbox (not links
# into ~/.local/share/flatpak). Exit 77: skipped (no loader, engine, data, Xvfb or GNOME 48).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
BIN="${GODNOTIC_LOADER_BIN:-$ROOT/loader/target/release/godnotic-loader}"
B="${GTK_BASEDIR:-${BASEDIR:-${UIB_BASEDIR:-}}}"
if [ ! -x "$BIN" ] || [ ! -x "$ENGINE" ] || [ ! -d "${B:-/nonexistent}/data" ] || ! command -v Xvfb > /dev/null \
	|| ! flatpak info org.gnome.Platform//48 > /dev/null 2>&1; then
	echo "SKIP: needs $BIN (loader/build.sh), ENGINE, GTK_BASEDIR, Xvfb and org.gnome.Platform//48"
	exit 77
fi
# under home: the Flatpak sandbox does not see the host's /tmp
WORK="${OUT:-$(mktemp -d "$HOME/.cache/godnotic-gtk-mpsort.XXXXXX")}"
mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"
[ -n "${OUT:-}" ] || trap 'rm -rf "$WORK"' EXIT
rm -rf "$WORK/ud" "$WORK/config" "$WORK/ub" "$WORK"/*.txt
python3 "$ROOT/tests/mpsort/gtk_world.py" "$WORK" 200
PORT=$((27800 + $$ % 100))
python3 "$ROOT/tests/srvlist/fakenet.py" --state "$WORK/world.json" --report "$WORK/fakenet.json" --master-port $PORT --master-latency 0.25 > "$WORK/fakenet.log" 2>&1 &
FAKE=$!
trap 'kill $FAKE 2> /dev/null; [ -n "${OUT:-}" ] || rm -rf "$WORK"' EXIT
cat > "$WORK/ud/data/mpsort-net.cfg" <<EOC
sv_master1 "127.0.0.1:$PORT"
sv_master2 ""
sv_master3 ""
sv_master4 ""
sv_masterextra1 ""
sv_masterextra2 ""
sv_masterextra3 ""
set net_slist_favorites ""
set net_slist_lan_repeats 0
set net_slist_seed 0
set uib_trace 1
set menu_slist_showempty 1
set menu_slist_showfull 1
set menu_slist_showlaggy 1
EOC
cat > "$WORK/start.sh" <<EOS
#!/bin/sh
export SDL_AUDIODRIVER=dummy SDL_VIDEODRIVER=offscreen ${LIBS:+LD_LIBRARY_PATH=$LIBS}
exec "$ENGINE" -xonotic -basedir "$B" -userdir "$WORK/ud" "\$@" +vid_fullscreen 0 +vid_width 1024 +vid_height 640 +set touch_profile_done 1 +set touch_setup_done 1 +set _touch_assets_ready 1 +set cl_allow_uid2name 0 +set cl_welcome 0 +exec mpsort-net.cfg +exec mpsort-later.cfg
EOS
chmod +x "$WORK/start.sh"
mkdir -p "$WORK/config" "$WORK/ub"
run() { # script -> $WORK/sort.txt
	sed "s#OUT/#$WORK/#g" "$1" > "$WORK/run.script"
	( cd "$ROOT/loader" && timeout 150 scripts/run-headless.sh --size 1280x800 -- flatpak run --filesystem=home --share=network \
		--env=GODNOTIC_START="$WORK/start.sh" --env=GODNOTIC_SKIP_DATA_CHECK=1 --env=GODNOTIC_TEST_NATIVE_BACKEND=1 \
		--env=XDG_CONFIG_HOME="$WORK/config" --env=GODNOTIC_USER_BASE="$WORK/ub" \
		--command="$BIN" org.gnome.Platform//48 --size=1180x760 --dark --script="$WORK/run.script" ) >> "$WORK/log.txt" 2>&1
}
: > "$WORK/ud/data/mpsort-later.cfg"
run "$ROOT/tests/mpsort/gtk-open-sort.script"
cp "$WORK/sort.txt" "$WORK/first.txt" 2> /dev/null || : > "$WORK/first.txt"
rm -f "$WORK/sort.txt"
# about 4 s after the restart run's list is checked: the engine itself goes back to Ping
echo 'defer 11 "uib_event {\"ev\":\"action\",\"node\":\"mp/servers\",\"action\":\"server.sort\",\"payload\":{\"sort\":\"ping\",\"desc\":false}}"' > "$WORK/ud/data/mpsort-later.cfg"
run "$ROOT/tests/mpsort/gtk-restart.script"
cp "$WORK/sort.txt" "$WORK/restart.txt" 2> /dev/null || : > "$WORK/restart.txt"
python3 - "$WORK" <<'EOP'
import re, sys
w = sys.argv[1]
def parse(f):
    out = []
    for line in open(f):
        m = re.match(r"t=(\d+) sort=(\S+) desc=(\d) top=(-?\d+) n=(\d+) rows=(.*)", line.strip())
        if m:
            rows = [r.rsplit(":", 1) for r in m.group(6).split(",") if ":" in r]
            out.append({"sort": m.group(2), "desc": m.group(3) == "1", "top": int(m.group(4)), "n": int(m.group(5)),
                        "players": [int(p) for _, p in rows if p.lstrip("-").isdigit() and int(p) >= 0]})
        else:
            out.append(None)
    return out
first, again = parse(w + "/first.txt"), parse(w + "/restart.txt")
fail = 0
def check(ok, what, detail=""):
    global fail
    print(("ok   " if ok else "FAIL ") + what + (f" ({detail})" if detail else ""))
    fail |= not ok
def most_first(s):
    return bool(s) and s["players"] == sorted(s["players"], reverse=True) and len(s["players"]) >= 8 and s["players"][0] > 0
def show(s):
    return "none" if not s else "%s desc=%d top=%d n=%d %s" % (s["sort"], s["desc"], s["top"], s["n"], s["players"][:6])
check(len(first) == 8 and all(first), "every step ran with the list on screen", "%d samples" % len(first))
if len(first) == 8 and all(first):
    o, p, sc, during, reo = first[0], first[1], first[2], first[3:7], first[7]
    check(o["sort"] == "ping" and not o["desc"] and o["n"] >= 190, "Multiplayer opens with the engine's sort shown in the header (Ping)", show(o))
    check(p["sort"] == "players" and p["desc"] and most_first(p), "a click on Players sorts by players, most first", show(p))
    check(all(s["sort"] == "players" and s["desc"] and most_first(s) for s in during),
          "while a Refresh streams the servers in, the sort stays Players, most first", "; ".join(show(s) for s in during))
    check(sc["top"] > 0 and all(s["top"] == sc["top"] for s in during), "and the list stays where it was scrolled",
          "scrolled to %d, then %s" % (sc["top"], [s["top"] for s in during]))
    check(during[-1]["n"] >= 190, "the refresh listed the servers", "n=%d" % during[-1]["n"])
    check(reo["sort"] == "players" and reo["desc"] and most_first(reo), "Back and Multiplayer again: still Players", show(reo))
check(len(again) == 2 and again[0] and again[0]["sort"] == "players" and again[0]["desc"] and most_first(again[0]),
      "after the app starts again Multiplayer opens by Players (saved)", show(again[0] if again else None))
last = again[1] if len(again) == 2 else None
check(bool(last) and last["sort"] == "ping" and not last["desc"] and not most_first(last),
      "the engine's own change of sort is followed in place (Ping)", show(last))
print("gtk-mpsort: all passed" if not fail else "gtk-mpsort: FAILED (" + w + "/log.txt)")
sys.exit(1 if fail else 0)
EOP
