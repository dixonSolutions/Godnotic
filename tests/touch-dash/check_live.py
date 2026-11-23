#!/usr/bin/env python3
"""Reads a scripted DASH run's client log (scripts/test-touch-dash-live.sh) and checks it.

  check_live.py on|off|floor CLIENT.LOG

on    the server has dodging: touch_dash_status reads it, a plain dash is a dodge that moves the
      player, and six forced boosts (Blaster and Devastator, up and forward) each fire and land
      near the predicted landing
off   the server has no dodging: status reads it, a tap with no goal boosts, and the six boosts land
floor a boost under the health floor is refused and nothing fires
"""
import re, sys

mode, path = sys.argv[1], sys.argv[2]
log = open(path, errors="replace").read().splitlines()
fails = 0

def check(cond, what):
    global fails
    print(("ok   " if cond else "FAIL ") + what)
    fails += 0 if cond else 1

def section(name):
    """Log lines after 'MARK name...' up to the next MARK."""
    out, on = [], False
    for l in log:
        if l.startswith("MARK "):
            on = l.startswith("MARK " + name)
            continue
        if on:
            out.append(l)
    return out

def boosts():
    res = []
    for l in log:
        m = re.match(r"MARK boost (\w+) (\w+)", l)
        if m:
            res.append({"dir": m.group(1), "wep": m.group(2), "lines": []})
        elif res:
            res[-1]["lines"].append(l)
    return res

status = [l for l in log if l.startswith("touch dash: boost ") and "dodging" in l]
if mode != "ui2":
    check(bool(status), "touch_dash_status printed")
if mode == "on":
    check("dodging -1" in status[0], "a stock server does not announce dodging: unknown at first")
    d = section("dash")
    check(any("tap: dodge" in l for l in d), "a plain tap with nothing to reach is a dodge")
    check(any("(dodged)" in l for l in d), "and the dodge moved the player")
    check(any("dodging 1" in l and "learned from dashes" in l for l in status[1:]), "which settles it: dodging learned as on")
if mode == "off":
    check("dodging -1" in status[0], "dodging unknown at first")
    check(any("(no dodge)" in l for l in section("dash1")), "the first dash did nothing")
    check(any("(no dodge)" in l for l in section("dash2")), "so did the second")
    check(any("dodging 0" in l and "learned from dashes" in l for l in status[1:]), "two that did nothing: dodging learned as off")
    a = section("auto")
    check(any("tap: boost" in l or "tap: nothing" in l for l in a) and not any("tap: dodge" in l for l in a),
          "and a tap is now a boost, or nothing where none reaches: never a dodge")

if mode in ("on", "off", "real"):
    rows = []
    results = []
    for b in boosts():
        txt = "\n".join(b["lines"])
        name = f'{b["wep"]} {b["dir"]}'
        if mode == "real" and "tap: boost" not in txt:
            print(f"skip {name}: not done on this server ({'; '.join(x[:90] for x in b['lines'] if 'tap:' in x)})")
            continue
        m = re.search(r"boost: (\w+) (up|forward).*?predicted landing (-?\d+) (-?\d+) (-?\d+) in ([\d.]+) s, costs (\d+) health", txt)
        fired = "fired " in txt
        l = re.search(r"landed: from (-?\d+) (-?\d+) (-?\d+) landed (-?\d+) (-?\d+) (-?\d+) predicted (-?\d+) (-?\d+) (-?\d+) error (\d+) \(horizontal (\d+), vertical (-?\d+)\)", txt)
        pm = re.search(r"push measured .* \(([\d.]+) of it\)", txt)
        check(bool(m), f"{name}: planned")
        check(fired, f"{name}: fired")
        if not (m and l):
            check(False, f"{name}: landed and measured")
            continue
        f0 = tuple(map(int, l.groups()[0:3])); lan = tuple(map(int, l.groups()[3:6])); pre = tuple(map(int, l.groups()[6:9]))
        err, herr, verr = int(l.group(10)), int(l.group(11)), int(l.group(12))
        travel = ((pre[0] - f0[0]) ** 2 + (pre[1] - f0[1]) ** 2) ** 0.5
        up = pre[2] - f0[2]
        rows.append((name, travel, up, herr, verr, err, pm.group(1) if pm else "-"))
        tol = max(130.0, 0.35 * travel)
        ok = herr <= tol and abs(verr) <= 80
        results.append(ok)
        print(("ok   " if ok else "MISS ") + f"{name}: landed {'within' if ok else 'OUTSIDE'} tolerance of the prediction (error {err}: horizontal {herr} of {travel:.0f} predicted, vertical {verr})")
    # a prediction from the client's own traces and the stock numbers is not exact: most must land close
    check((len(results) >= 5 if mode != "real" else len(results) >= 1) and sum(results) >= len(results) - 1, f"at most one boost of {len(results)} lands outside the tolerance ({sum(results)} inside)")
    print("numbers (units): weapon dir | predicted travel | predicted rise | horizontal error | vertical error | 3D error | push measured / predicted")
    for r in rows:
        print("  %-18s %5.0f %5d %5d %5d %5d   %s" % r)
if mode == "real":
    t = section("tap1")
    check(any("tap:" in l for l in t), "the first tap on DASH acts (a decision is made on the first tap)")
    check(sum(1 for l in t if "tap:" in l) == 1, "and only once: no double fire")
    for b in boosts():
        txt = "\n".join(b["lines"])
        if "tap: boost" in txt:
            check("fired " in txt, f'{b["wep"]} {b["dir"]}: the boost fired')
elif mode == "floor":
    r = section("refuse")
    check(any("tap: nothing" in l for l in r), "a boost under the health floor is refused")
    check(not any("fired " in l for l in log), "nothing was fired")

if mode == "ui1":
    check(any(l.startswith("MARK picker") for l in log), "the run reached the picker")
    chips = re.findall(r"picker chip (\w+) at ([\d.]+) ([\d.]+)", "\n".join(log))
    names = [c[0] for c in chips]
    check(all(n in names for n in ("Auto", "Up", "Forward")), f"holding DASH opens the picker with the direction chips ({names})")
    check(any(n in names for n in ("Blaster", "Devastator")), "and a chip for the weapon held")
    check(not any("tap:" in l for l in section("press")), "holding DASH does not dash")
    check(any(re.match(r"touch dash: boost 1", l) for l in log), "the weapon boost setting is on by default")
if mode == "qc":
    check(any("tap:" in l for l in log), "a tap on the port's CSQC DASH button reaches the smart dash (touch_dash)")
if mode == "ui2":
    d = [l for l in log if l.startswith("DIR ")]
    check(bool(d) and d[0].startswith("DIR 1 "), f"sliding onto UP and letting go sets the direction to up ({d[:1]})")
    if len(d) > 1:
        check("[blaster]" in d[1], f"and onto Blaster sets the weapon ({d[1]})")

print(f"check_live {mode}:", "FAILED" if fails else "all passed")
sys.exit(1 if fails else 0)
