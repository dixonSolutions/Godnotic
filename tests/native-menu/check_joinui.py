#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Checks a client log of scripts/test-native-menu-live.sh (case joinui).

    check_joinui.py <client.log> <ctf|dm> "<knob x> <knob y>"

Each MARK is followed by `touch_hud_state rects` (TOUCHHUD, TOUCHRECT lines) and
`uib_dump` (a snapshot of the UI bridge's description).
"""
import json
import re
import sys

log, gametype, knob = sys.argv[1], sys.argv[2], [float(v) for v in sys.argv[3].split()]
teams = gametype == "ctf"
text = open(log, encoding="utf-8", errors="replace").read().splitlines()
phase, hud, rects, nodes, modal = "start", {}, {}, {}, {}
console = (960.0, 600.0)
for line in text:
    m = re.match(r"^MARK (\w+)", line)
    if m:
        phase = m.group(1)
        rects.setdefault(phase, {})
        continue
    m = re.match(r"^TOUCHHUD mode=(\w+)", line)
    if m:
        hud.setdefault(phase, m.group(1))
    m = re.match(r"^TOUCHRECTS mode=\w+ size=(\d+)x(\d+)", line)
    if m:
        console = (float(m.group(1)), float(m.group(2)))
    m = re.match(r"^TOUCHRECT r (\S+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+)", line)
    if m and phase in rects:
        rects[phase][m.group(1)] = tuple(float(v) for v in m.groups()[1:])
    if line.startswith('{"uib":1,"t":"snapshot"') and phase not in nodes:
        try:
            nodes[phase] = {n["id"]: n for n in json.loads(line)["nodes"]}
        except ValueError:
            continue
        modal[phase] = nodes[phase].get("app", {}).get("props", {}).get("modal", [])

errors = []
def want(cond, what):
    print(("ok   " if cond else "FAIL ") + f"joinui {gametype}: " + what)
    if not cond:
        errors.append(what)

# at connect: the dialog by itself, in our style, and deliberate
want(hud.get("connect") in ("spectating", "following"), f"connected as an observer (mode {hud.get('connect')})")
want("join" in modal.get("connect", []), f"the join dialog opened by itself at connect (modal {modal.get('connect')})")
n = nodes.get("connect", {})
screen = n.get("join", {}).get("props", {})
want(screen.get("presentation") == "dialog" and screen.get("dismissable") is False,
     f"it is a dialog a tap beside it cannot close (dismissable {screen.get('dismissable')})")
want(screen.get("guardMs", 0) >= 300, f"it takes no touch for its first moments (guardMs {screen.get('guardMs')})")
want(screen.get("primary", {}).get("id") == "join.auto",
     f"the first choice is {'Auto-select' if teams else 'Join'} ({screen.get('primary', {}).get('label')!r})")
want("join/spectate" in n, "Spectate is there")
if teams:
    red, blue = n.get("join/teams/red", {}).get("props", {}), n.get("join/teams/blue", {}).get("props", {})
    want(red.get("swatch", "").startswith("#") and red.get("team") == "red" and blue.get("team") == "blue",
         f"the teams the server offers, in their colours (red {red.get('swatch')}, blue {blue.get('swatch')})")
    want(re.match(r"^\d+ players?$", red.get("subtitle", "")) is not None and re.match(r"^\d+ players?$", blue.get("subtitle", "")) is not None,
         f"each with its player count ({red.get('subtitle')!r}, {blue.get('subtitle')!r})")
    want("join/teams/yellow" not in n and "join/teams/pink" not in n, "and no team the server does not offer")
else:
    want(not any(k.startswith("join/teams") for k in n), "no team row in a game without teams")
    want(re.search(r"in the match|Nobody", screen.get("subtitle", "")) is not None, f"it says who plays ({screen.get('subtitle')!r})")

# taps beside it
want("join" in modal.get("scrim", []), f"taps beside it leave it up (modal {modal.get('scrim')})")
# the choice
want(hud.get("joined") == "playing", f"{'a team' if teams else 'Join'} puts the player in (mode {hud.get('joined')})")
want("join" not in modal.get("joined", ["join"]), f"and the dialog is gone (modal {modal.get('joined')})")
want("JOIN" not in rects.get("joined", {"JOIN": 1}), "no Swipe to join while playing")
# spectating by choice: no nagging, the control is there
want(hud.get("spectating") in ("spectating", "following"), f"spec: back to watching (mode {hud.get('spectating')})")
want("join" not in modal.get("spectating", ["join"]), f"spectating by choice: no dialog (modal {modal.get('spectating')})")
j = rects.get("spectating", {}).get("JOIN")
want(j is not None, f"Swipe to join is on the spectator HUD ({j})")
if j:
    kx, ky = knob[0] * console[0], knob[1] * console[1]
    want(j[0] <= kx <= j[0] + j[2] and j[1] <= ky <= j[1] + j[3],
         f"... where the scripted finger lands (knob {kx:.0f},{ky:.0f} in {j})")
    for name, r in rects.get("spectating", {}).items():
        if name != "JOIN" and r[0] < j[0] + j[2] and j[0] < r[0] + r[2] and r[1] < j[1] + j[3] and j[1] < r[1] + r[3]:
            want(False, f"Swipe to join overlaps {name}")
if teams:
    want("join" in modal.get("swiped", []), f"a swipe on it opens the team dialog in a team game (modal {modal.get('swiped')})")
    want(hud.get("rejoined") == "playing", f"Auto-select from there puts the player in (mode {hud.get('rejoined')})")
else:
    want(hud.get("rejoined") == "playing", f"a tap on it joins a game without teams (mode {hud.get('rejoined')})")
    want("join" not in modal.get("swiped", []), f"... with no dialog in between (modal {modal.get('swiped')})")
want("JOIN" not in rects.get("rejoined", {"JOIN": 1}), "and it is gone once playing")
sys.exit(1 if errors else 0)
