#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The GTK front end (loader/crates/gtk) on fixtures, on a private Xvfb: what is
# on screen at a phone, a phone on its side and a desktop window, read back as
# the text of the labels inside the window (script command `labels`).
#
#   Settings      the sections down the side and the chosen one's controls
#                 (vertical tabs; it used to be one blank row and an empty page);
#                 on a phone the list of sections is the page
#   Loading       one card: the step with its size and speed and one Cancel, and
#                 no banner saying the same above it
#   Map preview   on a phone on its side the title and Play are in view
#   Multiplayer   with a server chosen the engine's card for it (mp/details) is not docked into
#                 the page: the bottom bar (address, Create, Join, "i") stays on screen at
#                 1280x720, 1366x768, 1920x1080 and a phone; choosing opens the info dialog, Close
#                 keeps the choice, the "i" opens it again
#
#   scripts/test-gtk-screens.sh        (needs loader/target/release/godnotic-loader:
#                                       loader/build.sh, and org.gnome.Platform//48)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${GODNOTIC_LOADER_BIN:-$ROOT/loader/target/release/godnotic-loader}"
if [ ! -x "$BIN" ] || ! command -v Xvfb > /dev/null || ! flatpak info org.gnome.Platform//48 > /dev/null 2>&1; then
	echo "SKIP: needs $BIN (loader/build.sh), Xvfb and org.gnome.Platform//48"
	exit 0
fi
# under home: the Flatpak sandbox does not see the host's /tmp
WORK="$(mktemp -d "$HOME/.cache/godnotic-gtk-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }

labels() { # fixture WxH -> $WORK/<fixture>-<WxH>.txt
	local f=$1 size=$2 out="$WORK/$1-$2"
	mkdir -p "$out"
	printf 'wait 2000\nlabels %s/labels.txt\nsnapshot %s/shot.png\nquit\n' "$out" "$out" > "$out/run.script"
	FIXTURE="$f" timeout 90 "$ROOT/loader/scripts/drive.sh" "$out/run.script" "$out" "$size" dark > "$out/log.txt" 2>&1
	cp "$out/labels.txt" "$WORK/$f-$size.txt" 2> /dev/null || : > "$WORK/$f-$size.txt"
}
has() { grep -qxF "$2" "$WORK/$1.txt"; }
count() { grep -cxF "$2" "$WORK/$1.txt"; }

labels settings 1280x800
if has settings-1280x800 Video && has settings-1280x800 Mods && has settings-1280x800 "Resolution, effects, field of view" \
	&& has settings-1280x800 Resolution && has settings-1280x800 "Frame rate limit"; then
	ok "Settings at 1280x800: the sections with their subtitles beside Video's controls"
else
	bad "Settings at 1280x800: sections or controls missing ($(tr '\n' '|' < "$WORK/settings-1280x800.txt" | cut -c1-200))"
fi
labels settings 400x860
if has settings-400x860 Video && has settings-400x860 Privacy && ! has settings-400x860 Resolution; then
	ok "Settings at 400x860: one column, the list of sections is the page"
else
	bad "Settings at 400x860: expected the section list alone ($(tr '\n' '|' < "$WORK/settings-400x860.txt" | cut -c1-200))"
fi

labels seq-download 1280x800
n=$(count seq-download-1280x800 Cancel); j=$(count seq-download-1280x800 "Joining Clan Wars #12 (203.0.113.12:26000)")
if [ "$n" = 1 ] && [ "$j" = 1 ] && grep -q "^Downloading map · .* MB of 40 MB" "$WORK/seq-download-1280x800.txt"; then
	ok "Loading: one card with the step, size and speed, one Cancel, the title once (no banner repeating it)"
else
	bad "Loading: $n Cancel, $j titles ($(tr '\n' '|' < "$WORK/seq-download-1280x800.txt" | cut -c1-200))"
fi

labels map-preview 915x412
if has map-preview-915x412 Stormkeep && has map-preview-915x412 "Play Deathmatch"; then
	ok "Map preview on a phone on its side: the title and Play are in view"
else
	bad "Map preview at 915x412: Play is out of view ($(tr '\n' '|' < "$WORK/map-preview-915x412.txt" | cut -c1-200))"
fi

# Multiplayer with a server chosen: the bar on screen at common sizes, the info dialog and its "i"
for size in 1280x720 1366x768 1920x1080 400x860; do
	labels servers-chosen "$size"
	f=servers-chosen-$size
	if has $f Create && has $f Join && ! has $f "Players · score · team"; then
		ok "Multiplayer with a server chosen at $size: the bottom bar (Create, Join) is on screen"
	else
		bad "Multiplayer with a server chosen at $size: bar off screen or card docked ($(tr '\n' '|' < "$WORK/$f.txt" | cut -c1-200))"
	fi
done
dialog() { # WxH: choose a row (opens the dialog), then the labels while it is up, after Close, after the "i"
	local size=$1 out="$WORK/dialog-$1"
	mkdir -p "$out"
	printf 'wait 2500\nrow mp/servers 2\nwait 1500\nlabels %s/open.txt\nsnapshot %s/open.png\nclick mp/servers:info-close\nwait 800\nlabels %s/closed.txt\nclick mp/join:info\nwait 1200\nlabels %s/again.txt\nsnapshot %s/again.png\nquit\n' "$out" "$out" "$out" "$out" "$out" > "$out/run.script"
	FIXTURE=seq-server-select timeout 90 "$ROOT/loader/scripts/drive.sh" "$out/run.script" "$out" "$size" dark > "$out/log.txt" 2>&1
}
for size in 1280x720 400x860; do
	dialog "$size"
	o="$WORK/dialog-$size"
	if grep -qxF Close "$o/open.txt" && grep -qxF "Join" "$o/open.txt" && ! grep -qxF Close "$o/closed.txt" && grep -qxF Join "$o/closed.txt" && grep -qxF Close "$o/again.txt"; then
		ok "Server info at $size: choosing opens the dialog, Close leaves the list with the bar, the \"i\" opens it again"
	else
		bad "Server info at $size: open=$(tr '\n' '|' < "$o/open.txt" | cut -c1-120) closed=$(tr '\n' '|' < "$o/closed.txt" | cut -c1-120)"
	fi
done

[ $fail = 0 ] && echo "gtk-screens: all passed"
exit $fail
