#!/usr/bin/env python3
"""Checks one scripted client log of scripts/test-touch-orbit.sh.
usage: check_live.py touch|desk dead|inter|vote LOG
The script prints 'MARK name' followed by the state line (touch_orbit_state):
TOUCHORBIT ctx= zoom= want= dist= yaw= pitch= engaged= pinch=
'OWN=n' is mv_ownvote, 'CVARS death dist' the player's cl_eventchase_*."""
import re, sys

kind, phase, log = sys.argv[1], sys.argv[2], sys.argv[3]
marks, cur, own, cvars = {}, None, {}, {}
mouse_attached = False
for line in open(log, errors="replace"):
    if line.startswith("VM mouse=1"):
        mouse_attached = True  # this host has a mouse: the touch layer leaves the look to it
    m = re.match(r"MARK (\S+)", line)
    if m:
        cur = m.group(1)
        continue
    m = re.match(r"TOUCHORBIT (.*)", line)
    if m and cur and cur not in marks:
        marks[cur] = {k: float(v) for k, v in re.findall(r"(\w+)=(-?[\d.]+)", m.group(1))}
        continue
    m = re.match(r"OWN=(-?\d+)", line)
    if m and cur:
        own[cur] = int(m.group(1))
    m = re.match(r"CVARS (\S+) (\S+)", line)
    if m and cur:
        cvars[cur] = (float(m.group(1)), float(m.group(2)))

fails = 0
def check(label, ok, detail=""):
    global fails
    print(("PASS: " if ok else "FAIL: ") + label + ("" if ok else "  " + str(detail)))
    fails += 0 if ok else 1
def get(name):
    if name not in marks:
        check("state printed at " + name, False, "missing")
        return None
    return marks[name]
def turned(a, b):
    return abs(((b["yaw"] - a["yaw"] + 180) % 360) - 180) > 5 or abs(b["pitch"] - a["pitch"]) > 3

d0 = get("dead0") if phase == "dead" else None
if d0:
    check("dead: orbit context, nothing changed yet", d0["ctx"] == 1 and d0["zoom"] == 1 and d0["engaged"] == 0, d0)
    if kind == "touch":
        d1 = get("dead_pinch_out")
        if d1:
            check("dead: spreading two fingers zooms in and engages the chase", d1["zoom"] < 0.9 and d1["engaged"] == 1, d1)
            check("dead: the pinch moved the player's chase distance", d1["dist"] < 140 * 0.95, d1)
            check("dead: pinch over, no pinch flag left", d1["pinch"] == 0, d1)
        d2 = get("dead_pinch_in")
        if d2:
            check("dead: closing two fingers zooms back out", d1 and d2["zoom"] > d1["zoom"] * 1.5, (d1, d2))
        dd, d3 = get("dead_drag0"), get("dead_drag1")
        if dd and d3 and mouse_attached:
            print("SKIP: dead one-finger drag: this host has a mouse, so the stock touch look is off (vid_mouse_confirmed)")
        elif dd and d3:
            check("dead: a one-finger drag turns the view", turned(dd, d3), (dd, d3))
    else:
        d1 = get("dead_wheel")
        if d1:
            check("dead: wheel zooms in and engages the chase", d1["zoom"] < 0.8 and d1["engaged"] == 1 and d1["dist"] < 140, d1)
    if "dead_cvars" in cvars:
        check("dead: chase on at once, distance changed", cvars["dead_cvars"][0] == 1 and cvars["dead_cvars"][1] != 140, cvars["dead_cvars"])
    if "alive_cvars" in cvars:
        check("respawn: the player's own cl_eventchase_death and distance are back", cvars["alive_cvars"] == (2.0, 140.0), cvars["alive_cvars"])
    al = get("alive")
    if al:
        check("respawn: a tap on RESPAWN still respawns, orbit stands down", al["ctx"] == 0 and al["zoom"] == 1, al)

if kind == "touch" and not mouse_attached and "alive_drag0" in marks and "alive_drag1" in marks:
    check("alive after respawn: the look drag turns the view as before", turned(marks["alive_drag0"], marks["alive_drag1"]), (marks["alive_drag0"], marks["alive_drag1"]))
i0 = get("inter0") if phase == "inter" else None
if i0:
    check("end of match: orbit context", i0["ctx"] == 2, i0)
    if kind == "touch":
        i1, i2 = get("inter_drag"), get("inter_pinch")
        if i1:
            check("end of match: a drag turns the view", turned(i0, i1), (i0, i1))
        if i2:
            check("end of match: a pinch zooms", i2["zoom"] < 0.6, i2)
    else:
        i2 = get("inter_wheel")
        if i2:
            check("end of match: the wheel zooms", i2["zoom"] < 0.8, i2)

v0 = get("vote0") if phase == "vote" else None
if v0:
    check("vote: orbit context", v0["ctx"] == 2, v0)
    v1 = get("vote_drag")
    if v1:
        check("vote: a drag from empty space turns the view", turned(v0, v1), (v0, v1))
    if "vote_drag" in own:
        check("vote: that drag did not vote", own["vote_drag"] == -1, own)
    taps = [n for n in ("vote_tap1", "vote_tap2", "vote_tap3", "vote_tap4") if n in marks]
    check("vote: a tap on an entry still votes", any(own.get(n, -1) >= 0 for n in taps), own)
    still = [marks[n] for n in taps if marks[n]["ctx"] == 2]
    if still and v1:
        check("vote: taps do not move the camera", all(not turned(v1, m) for m in still), (v1, still))
    if kind == "touch" and get("vote_pinch"):
        check("vote: a pinch on empty space zooms", marks["vote_pinch"]["zoom"] < 0.9, marks["vote_pinch"])
print("touch orbit live %s %s: %s" % (kind, phase, "FAILED" if fails else "all checks passed"))
sys.exit(1 if fails else 0)
