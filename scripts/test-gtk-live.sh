#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The Linux app (loader/crates/gtk) with the real engine as its UI-bridge back end,
# on a private Xvfb, through a whole session: launch -> a local match -> Escape ->
# the pause screen -> Escape -> the match -> Leave -> Home -> Multiplayer -> join a
# local server -> the join dialog over the match -> Auto-select -> playing.
#
#   one window   Exactly one top-level window from launch to the end: the game's
#                window lives inside the app's (X11 child), so nothing appears or
#                disappears when a match starts, pauses or ends (sampled every 50 ms
#                with tests/gtk-live/xwin.py). The screen is recorded (ffmpeg
#                x11grab) and no frame is a flash of plain black or white.
#   pause        Escape in a match (a real SDL key event, tests/touch-controls/finj.c)
#                pauses the local game and shows the app's pause screen (it used to
#                pause with no menu: the app stayed hidden, app.context said "game");
#                Escape in the app (its own key handler) resumes the match.
#   dialogs      The join dialog (Xonotic's team question, modal "join") that opens by
#                itself when the server is joined (a touchscreen: the shim reports one)
#                shows in the app, over the match; answering it returns to the match.
#   focus        The app's window losing the keyboard focus (another window clicked)
#                mutes the game and gets the idle frame rate; getting it back unmutes
#                (snd_mutewhenidle through the app's caps; `uib_window` reports it).
#   settings     On a PC with no touchscreen, Settings has the touch section
#                (Controls, as on Android) with its on-screen controls switch, and says
#                when its rows apply instead of leaving them out.
#
#   scripts/test-gtk-live.sh
#
# Needs loader/target/release/godnotic-loader (loader/build.sh), the engine (ENGINE,
# default build/bin/xonotic), Xvfb, ffmpeg, org.gnome.Platform//48, sdl2-config, and
# the stock packs: STOCK=<folder with xonotic-*-data.pk3, -maps.pk3 and the fonts>
# (default ~/.cache/godnotic-infra/repack/src). OUT=<dir> keeps the logs, the
# recording and the screenshots; CPUS pins the run (taskset list). Exit 77: skipped.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${GODNOTIC_LOADER_BIN:-$ROOT/loader/target/release/godnotic-loader}"
ENGINE="${ENGINE:-$ROOT/build/bin/xonotic}"
STOCK="${STOCK:-$HOME/.cache/godnotic-infra/repack/src}"
RUNTIME="${RUNTIME:-org.gnome.Platform//48}"
for need in Xvfb ffmpeg python3 cc; do
	command -v "$need" > /dev/null || { echo "SKIP: needs $need"; exit 77; }
done
if [ ! -x "$BIN" ] || [ ! -x "$ENGINE" ] || ! ls "$STOCK"/xonotic-*-maps*.pk3 > /dev/null 2>&1 || ! flatpak info "$RUNTIME" > /dev/null 2>&1; then
	echo "SKIP: needs $BIN, $ENGINE, the stock packs in $STOCK and $RUNTIME"
	exit 77
fi
SDLCFG="$(command -v sdl2-config || echo "$HOME/.cache/xt-assets/bin/sdl2-config")"
[ -x "$SDLCFG" ] || { echo "SKIP: sdl2-config builds the input shim"; exit 77; }
RUN=(); [ -n "${CPUS:-}" ] && RUN=(taskset -c "$CPUS")

# under home: the Flatpak sandbox does not see the host's /tmp
if [ -n "${OUT:-}" ]; then WORK="$OUT"; rm -rf "$WORK"; mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"
else WORK="$(mktemp -d "$HOME/.cache/godnotic-gtk-live.XXXXXX")"; fi
pids=()
cleanup() {
	for p in "${pids[@]}"; do kill "$p" 2> /dev/null; done
	[ -n "${XVFB:-}" ] && { pkill -f -- "DISPLAY=:$N " 2> /dev/null; kill "$XVFB" 2> /dev/null; }
	wait 2> /dev/null
	[ -z "${OUT:-}" ] && rm -rf "$WORK"
}
trap cleanup EXIT INT TERM
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

B="$WORK/base"; mkdir -p "$B/data" "$WORK/ud" "$WORK/sv" "$WORK/config" "$WORK/ub"
for f in "$STOCK"/font-*.pk3 "$STOCK"/xonotic-*-data*.pk3 "$STOCK"/xonotic-*-maps*.pk3; do ln -sfn "$f" "$B/data/"; done
ln -sfn "$ROOT/engine/data/xonotic-data.pk3dir" "$B/data/zz-xonotic-data.pk3dir"
cc -shared -fPIC -O2 -o "$WORK/finj.so" "$ROOT/tests/touch-controls/finj.c" $("$SDLCFG" --cflags) -ldl || { echo "FAIL input shim"; exit 1; }

# a local server to join (the same game code; one bot so the match runs). No master
# servers on either side: the run stays on this machine. sv_spectate 1: a player who
# connects watches until they join (no autojoin), so the join dialog opens.
PORT=$((26900 + RANDOM % 90))
"${RUN[@]}" "$ENGINE" -dedicated -xonotic -basedir "$B" -userdir "$WORK/sv" +set port $PORT +set sv_public 0 +set sv_spectate 1 \
	+set sv_master1 "" +set sv_master2 "" +set sv_master3 "" +set sv_master4 "" +set bot_number 1 +set skill 1 +set g_warmup 0 +set timelimit_override 20 +set g_maplist stormkeep +map stormkeep \
	> "$WORK/server.log" 2>&1 &
pids+=($!)

# Engine timeline (seconds after xt_go, which the engine sets 8 s after its local map loaded):
#   0 report, focus away at ~1.5 (this script), 3 report, focus back at ~4.5, 6 report,
#   7 Escape (the pause screen; the app's Escape resumes it), 11 report, 14 Escape again
#   (the app then leaves the match from its pause screen), later reports.
cat > "$WORK/inject.txt" << 'EOF'
0 cmd echo MARK go; uib_window
3 cmd echo MARK away; uib_window
6 cmd echo MARK back; uib_window
7 key 27
8.5 cmd echo MARK paused; uib_window; touch_pause_status
11 cmd echo MARK resumed; uib_window; touch_pause_status
14 key 27
EOF
cat > "$WORK/start.sh" << EOS
#!/bin/sh
# what the app starts as its back end (GODNOTIC_START): the engine with a fresh profile
export LD_PRELOAD="$WORK/finj.so" XT_INJECT="$WORK/inject.txt" SDL_AUDIODRIVER=dummy
exec "$ENGINE" -xonotic -basedir "$B" -userdir "$WORK/ud" "\$@" +vid_width 1024 +vid_height 640 \\
	+set vid_touchscreen 1 +set vid_touchscreen_detected 1 +set touch_profile_done 1 +set touch_setup_done 1 +set _touch_assets_ready 1 +set cl_allow_uid2name 0 \\
	+set cl_welcome 0 +set developer 1 +set xt_go 0 +set touch_join_dialog_auto 1 +set snd_mutewhenidle 1 \\
	+set bot_number 0 +set sv_master1 "" +set sv_master2 "" +set sv_master3 "" +set sv_master4 "" \\
	+set sv_masterextra1 "" +set sv_masterextra2 "" +set sv_masterextra3 "" +map stormkeep +defer 8 "set xt_go 1"
EOS
chmod +x "$WORK/start.sh"
cat > "$WORK/run.script" << EOF
wait 1500
visible $WORK/vis.txt
waitgame 90000
visible $WORK/vis.txt
waitmenu 40000
wait 1200
visible $WORK/vis.txt
labels $WORK/pause-labels.txt
snapshot $WORK/pause-gtk.png
key Escape
waitgame 8000
wait 500
visible $WORK/vis.txt
waitmenu 30000
wait 1000
action pause/actions/disconnect disconnect
waittop home 20000
wait 1500
visible $WORK/vis.txt
action home/tiles/mp mp
waittop mp 10000
wait 800
type mp/join/address 127.0.0.1:$PORT
wait 600
action mp/join/go join
waittop join 60000
wait 1500
visible $WORK/vis.txt
labels $WORK/join-labels.txt
snapshot $WORK/join-gtk.png
action join join.auto
waitgame 20000
wait 3000
visible $WORK/vis.txt
quit
EOF

N=90
while [ -e "/tmp/.X11-unix/X$N" ] || [ -e "/tmp/.X$N-lock" ]; do N=$((N + 1)); done
Xvfb ":$N" -screen 0 1024x640x24 -nolisten tcp > /dev/null 2>&1 &
XVFB=$!
for _ in $(seq 50); do [ -e "/tmp/.X11-unix/X$N" ] && break; sleep 0.1; done
XENV=(env -u WAYLAND_DISPLAY -u XAUTHORITY -u DBUS_SESSION_BUS_ADDRESS DISPLAY=":$N")
"${XENV[@]}" sh -c '[ -z "${WAYLAND_DISPLAY:-}" ] && [ "$DISPLAY" = ":'"$N"'" ]' || { echo "FAIL: environment still points at the user's display"; exit 1; }

"${XENV[@]}" python3 "$ROOT/tests/gtk-live/xwin.py" watch "$WORK/windows.txt" 200 &
WATCH=$!
pids+=($WATCH)
"${XENV[@]}" ffmpeg -loglevel error -f x11grab -video_size 1024x640 -framerate 10 -i ":$N" -t 200 -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$WORK/session.mp4" &
FFMPEG=$!
pids+=($FFMPEG)

# The focus driver: what a window manager does when another window is clicked, then this one.
(
	for _ in $(seq 1200); do grep -q "MARK go" "$WORK/log.txt" 2> /dev/null && break; sleep 0.1; done
	sleep 1.5; "${XENV[@]}" python3 "$ROOT/tests/gtk-live/xwin.py" focus none > /dev/null
	sleep 3; "${XENV[@]}" python3 "$ROOT/tests/gtk-live/xwin.py" focus Godnotic > /dev/null
) &
pids+=($!)

# No display socket in the sandbox: the Xvfb is reached through its abstract socket and
# DISPLAY is set by `env` inside (flatpak ignores --env=DISPLAY and, with --socket=x11,
# hands the sandbox the user's own display: loader/scripts/run-headless.sh).
SANDBOX=(flatpak run --nosocket=wayland --nosocket=fallback-x11 --nosocket=x11 --nosocket=session-bus --share=network
	--share=ipc --unset-env=WAYLAND_DISPLAY --unset-env=DISPLAY --filesystem=home)
INSIDE=(DISPLAY=":$N" GDK_BACKEND=x11 NO_AT_BRIDGE=1 GTK_A11Y=none GSK_RENDERER="${GSK_RENDERER:-cairo}")
seen="$("${SANDBOX[@]}" --command=env "$RUNTIME" "${INSIDE[@]}" sh -c 'echo "$DISPLAY ${WAYLAND_DISPLAY:-} $(ls /tmp/.X11-unix 2>/dev/null)"' 2>/dev/null)"
[ "$seen" = ":$N  " ] || { echo "FAIL: the sandbox would see '$seen', not only :$N; refusing"; exit 1; }
timeout -k 5 190 "${RUN[@]}" "${SANDBOX[@]}" \
	--env=GODNOTIC_START="$WORK/start.sh" --env=GODNOTIC_SKIP_DATA_CHECK=1 --env=GODNOTIC_TEST_NATIVE_BACKEND=1 \
	--env=XDG_CONFIG_HOME="$WORK/config" --env=GODNOTIC_USER_BASE="$WORK/ub" \
	--command=env "$RUNTIME" "${INSIDE[@]}" "$BIN" --size=1024x640 --dark --script="$WORK/run.script" > "$WORK/log.txt" 2>&1
echo "app exit $?" >> "$WORK/log.txt"
sleep 0.5
kill $WATCH 2> /dev/null
kill -INT $FFMPEG 2> /dev/null; wait $FFMPEG 2> /dev/null

# Settings on a PC with no touchscreen (a fresh profile, no input shim): the touch
# section is there, as on Android (Settings > Controls), with the on-screen controls
# switch and a first line saying when its rows apply, rather than being left out.
mkdir -p "$WORK/ud2"
cat > "$WORK/start2.sh" << EOS
#!/bin/sh
export SDL_AUDIODRIVER=dummy
exec "$ENGINE" -xonotic -basedir "$B" -userdir "$WORK/ud2" "\$@" +vid_width 1024 +vid_height 640 \\
	+set touch_profile_done 1 +set touch_setup_done 1 +set _touch_assets_ready 1 +set developer 1 \\
	+set sv_master1 "" +set sv_master2 "" +set sv_master3 "" +set sv_master4 "" \\
	+set sv_masterextra1 "" +set sv_masterextra2 "" +set sv_masterextra3 "" \\
	+defer 2 "echo TOUCHSCREEN \\\$vid_touchscreen \\\$vid_touchscreen_detected"
EOS
chmod +x "$WORK/start2.sh"
printf '%s\n' "wait 2500" "action home/nav/settings settings" "waittop settings 15000" "wait 800" \
	'value settings/split "touch"' "wait 1500" "labels $WORK/settings-labels.txt" "snapshot $WORK/settings-touch.png" \
	"quit" > "$WORK/settings.script"
timeout -k 5 120 "${RUN[@]}" "${SANDBOX[@]}" \
	--env=GODNOTIC_START="$WORK/start2.sh" --env=GODNOTIC_SKIP_DATA_CHECK=1 --env=GODNOTIC_TEST_NATIVE_BACKEND=1 \
	--env=XDG_CONFIG_HOME="$WORK/config" --env=GODNOTIC_USER_BASE="$WORK/ub" \
	--command=env "$RUNTIME" "${INSIDE[@]}" "$BIN" --size=1024x640 --dark --script="$WORK/settings.script" > "$WORK/settings-log.txt" 2>&1

python3 "$ROOT/tests/gtk-live/check.py" "$WORK" || fail=1
[ $fail = 0 ] && echo "gtk live: all checks passed" || echo "gtk live: FAILED (OUT=<dir> keeps the logs)"
exit $fail
