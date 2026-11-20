"""Music state sequence of a client log (MUSIC: lines, MARK-* markers, `FE> app` snapshots).

  python3 -I music_check.py <client.log> remote|local

remote: menu theme starts in the menu; after MARK-JOIN the theme never starts again; the first
        map track starts before the first `ingame` snapshot; the map change starts the next
        map's track (MUSIC: map track ... starts during the join) and no theme comes back.
local:  same for a local game started from the menu (MARK-JOIN is the `map` command).
"""
import json, re, sys

log, kind = sys.argv[1:3]
seq = []
for line in open(log, errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("MUSIC: start menu theme") or line.startswith("MUSIC: theme not playing"):
        seq.append("theme")
    elif line.startswith("MUSIC: stop menu theme"):
        seq.append("stop")
    elif re.match(r"MUSIC: map track \S+ starts during the join", line):
        seq.append("map:" + line.split()[3])
    elif line.startswith("MARK-JOIN"):
        seq.append("JOIN")
    elif line.startswith("FE> app "):
        try:
            st = json.loads(line[8:]).get("state")
        except ValueError:
            continue
        if st == "ingame" and (not seq or seq[-1] != "ingame"):
            seq.append("ingame")
print("music sequence:", " ".join(seq))
fails = []
if "JOIN" not in seq or "theme" not in seq or seq.index("theme") > seq.index("JOIN"):
    fails.append("the menu theme did not play before the join")
else:
    after = seq[seq.index("JOIN") + 1:]
    if "theme" in after:
        fails.append("the menu theme started again during/after the join")
    if kind == "remote" and "stop" not in after:
        fails.append("the menu theme was not stopped by the join")
    maps = [x for x in after if x.startswith("map:")]
    if not maps:
        fails.append("no map track started")
    elif "ingame" in after and after.index("ingame") < after.index(maps[0]):
        fails.append("the map's music started only after the first world frame")
    if kind == "remote" and len(set(maps)) < 2:
        fails.append("the map change did not start the next map's track: %s" % maps)
for f in fails:
    print("FAIL:", f)
if not fails:
    print("ok   %s music: theme in the menu, stopped by the join, map track before the first world frame" % kind)
sys.exit(1 if fails else 0)
