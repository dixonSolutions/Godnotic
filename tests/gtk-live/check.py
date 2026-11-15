#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks a scripts/test-gtk-live.sh run: check.py <work dir>
# Reads vis.txt (the app's own record: window shown, who has the screen, top screen),
# log.txt (the app's and the engine's output: uib_window and touch_pause_status at each
# MARK), windows.txt (the top-level windows every 50 ms) and session.mp4 (the screen).
import os
import re
import subprocess
import sys

work = sys.argv[1]
fail = 0


def ok(m):
    print("ok   " + m)


def bad(m):
    global fail
    fail = 1
    print("FAIL " + m)


def read(name):
    try:
        with open(os.path.join(work, name), errors="replace") as f:
            return f.read()
    except OSError:
        return ""


log = read("log.txt")
vis = [line.split() for line in read("vis.txt").splitlines() if line.strip()]


def mark(name):
    """The engine's report lines after MARK <name>, up to the next MARK."""
    m = re.search(r"MARK %s\b(.*?)(?=MARK |\Z)" % name, log, re.S)
    return m.group(1) if m else ""


def window_line(block):
    m = re.search(r"uib window: .*", block)
    return m.group(0) if m else ""


# --- who shows: the app's own record
def vis_ok(i, playing, top, modal=None):
    if i >= len(vis):
        return False
    v = vis[i]
    if v[0] != "visible" or v[1] != ("game" if playing else "pages") or v[2] != "embedded" or v[3] != top:
        return False
    return modal is None or (len(v) > 4 and v[4] == modal)


steps = [
    (1, True, "pause", None, "a local match: the game has the screen inside the app's one window"),
    (2, False, "pause", None, "Escape in the match: the app's pause screen shows"),
    (3, True, "pause", None, "Escape in the app: back in the match"),
    (4, False, "home", None, "Leave match: Home"),
    (5, False, "pause", "join", "joined a server: its join dialog shows in the app, over the match"),
    (6, True, "pause", None, "Auto-select: playing on the server"),
]
for i, playing, top, modal, what in steps:
    if vis_ok(i, playing, top, modal):
        ok(what)
    else:
        bad("%s (got %s)" % (what, " ".join(vis[i]) if i < len(vis) else "nothing"))

pl = read("pause-labels.txt").splitlines()
if "Resume" in pl and "Leave match" in pl:
    ok("the pause screen has Resume and Leave match")
else:
    bad("pause screen labels: %s" % "|".join(pl))
jl = read("join-labels.txt").splitlines()
if "Join the match" in jl and ("Join" in jl or any(x.startswith("Auto-select") for x in jl)) and "Spectate" in jl:
    ok("the join dialog shows its question and choices")
else:
    bad("join dialog labels: %s" % "|".join(jl))

# --- the engine's side
go, away, back, paused, resumed = (window_line(mark(n)) for n in ("go", "away", "back", "paused", "resumed"))
if "shown (embedded)" in go and "menu 0" in go:
    ok("engine: the game window is inside the app's window and shown in the match")
else:
    bad("engine at the match start: %r" % go)
if "sound muted" in away and "vid_activewindow 0" in away:
    ok("focus elsewhere: the game is muted and idles")
else:
    bad("focus elsewhere: %r" % away)
if "sound on" in back and "vid_activewindow 1" in back:
    ok("focus back: sound on, full frame rate")
else:
    bad("focus back: %r" % back)
pb = mark("paused")
if "hidden (embedded)" in paused and "menu 1" in paused and re.search(r"touch pause: host \d, server 1", pb):
    ok("Escape: the local game is paused under the pause screen")
else:
    bad("after Escape: %r / %r" % (paused, re.findall(r"touch pause: host.*", pb)[:1]))
rb = mark("resumed")
if "shown (embedded)" in resumed and "menu 0" in resumed and re.search(r"touch pause: host 0, server 0", rb):
    ok("Escape in the app: the match runs again")
else:
    bad("after the app's Escape: %r / %r" % (resumed, re.findall(r"touch pause: host.*", rb)[:1]))

# --- Settings on a PC with no touchscreen
sl = read("settings-labels.txt").splitlines()
touch = re.search(r"TOUCHSCREEN (\d+) (\d+)", read("settings-log.txt"))
want = ["Controls", "Touch controls", "On-screen controls", "Show the on-screen controls", "Automatic", "Always", "Never"]
missing = [w for w in want if w not in sl]
note = any(x.startswith("No touchscreen found on this device") for x in sl)
if not missing and (note or (touch and touch.group(2) != "0")):
    ok("Settings on Linux: the touch section (Controls) with the on-screen controls switch%s" % (", and the no-touchscreen note" if note else ""))
else:
    bad("Settings on Linux: missing %s, note %s, touchscreen %s" % (missing, note, touch.groups() if touch else "?"))

# --- one top-level window the whole time
t0 = None
samples = []
for line in read("windows.txt").splitlines():
    if line.startswith("# t0"):
        t0 = float(line.split()[2])
        continue
    p = line.split()
    if len(p) >= 2:
        samples.append((float(p[0]), int(p[1]), p[2:]))
seen = [i for i, s in enumerate(samples) if s[1] > 0]
if not seen:
    bad("no top-level window was ever mapped")
else:
    a, b = seen[0], seen[-1]
    span = samples[a:b + 1]
    counts = sorted(set(s[1] for s in span))
    gaps = [s[0] for s in span if s[1] != 1]
    if counts == [1]:
        ok("one top-level window from launch to quit (%d samples over %.0f s: %s)" % (len(span), span[-1][0] - span[0][0], " ".join(span[0][2])))
    else:
        bad("top-level windows over the session: counts %s, first off-count at %s s (%s)" % (counts, gaps[:3], [s for s in span if s[1] != 1][:3]))

# --- no flash of plain black or white while the window is up
mp4 = os.path.join(work, "session.mp4")
if os.path.exists(mp4) and seen:
    out = subprocess.run(["ffprobe", "-v", "error", "-f", "lavfi", "-i", "movie=%s,signalstats" % mp4.replace(":", "\\:"),
                          "-show_entries", "frame=pts_time:frame_tags=lavfi.signalstats.YAVG,lavfi.signalstats.YMAX",
                          "-of", "csv=p=0"], capture_output=True, text=True).stdout
    frames = []
    for line in out.splitlines():
        p = line.split(",")
        try:
            frames.append((float(p[0]), float(p[1]), float(p[2])))
        except (ValueError, IndexError):
            pass
    start, end = samples[seen[0]][0] + 1.0, samples[seen[-1]][0] - 0.5
    inside = [f for f in frames if start <= f[0] <= end]
    flashes = [f for f in inside if f[2] < 20 or f[1] > 245]
    if inside and not flashes:
        ok("no plain black or white frame in %d recorded frames" % len(inside))
    elif not inside:
        bad("the recording has no frames (%d decoded)" % len(frames))
    else:
        bad("%d plain black/white frames, first at %.1f s" % (len(flashes), flashes[0][0]))
    # the screenshot over a match: the frame at the moment the app recorded "game" (step 1 and 6)
    for i, name in ((1, "match.png"), (6, "match-online.png")):
        if i < len(vis) and t0 is not None and vis[i][-1].startswith("@"):
            t = float(vis[i][-1][1:]) - t0
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", "%.2f" % (t + 2.0), "-i", mp4,
                            "-frames:v", "1", os.path.join(work, name)])
sys.exit(fail)
