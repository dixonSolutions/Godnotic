"""Assertions over the front-end bridge log (`FE> topic {json}` lines) of one client that joins a
server, rides a map change on it (changelevel), then joins a second server on another map.

  python3 -I check.py <client.log> <mapA> <mapB> <mapC>

Join A = mapA then mapB (changelevel), join B = mapC (a new connect while still connected to A),
then a lost connection (MARK-FREEZE) that the client rejoins by itself.
Exit 0 and one `ok` line per rule, else the failing rule and the offending snapshots.
"""
import json, sys

log, map_a, map_b, map_c = sys.argv[1:5]
events = []
for line in open(log, errors="replace"):
    if line.startswith("FE> "):
        topic, _, body = line[4:].partition(" ")
        try:
            events.append((topic, json.loads(body)))
        except ValueError:
            pass
    elif line.startswith("MARK-"):
        events.append(("mark", {"name": line.strip()}))

fails = []
def bad(msg, *ev):
    fails.append(msg)
    print("FAIL:", msg)
    for e in ev[:4]:
        print("   ", json.dumps(e)[:240])

def base(path):
    return path.rsplit("/", 1)[-1]

# 1. picture identity: a snapshot never carries a picture of another map than the one it names,
#    never a generic stand-in, and a pending join (new connect) never inherits the old join's map.
second = False
seen_pics = {}
for topic, d in events:
    if topic == "mark" and d["name"] == "MARK-SECOND-JOIN":
        second = True
    if topic != "connect":
        continue
    shot, jm, kind = d.get("levelshot", ""), d.get("join_map", ""), d.get("levelshot_kind", "")
    if kind == "generic":
        bad("generic stand-in picture", d)
    if shot and not jm:
        bad("a picture with no map named", d)
    if shot and jm and ("_" + jm) not in base(shot) and ("/" + jm) not in shot and jm not in base(shot):
        bad("picture %s is not the map %s's" % (base(shot), jm), d)
    if shot:
        seen_pics.setdefault(jm, set()).add(base(shot))
    if second and d.get("stage") == "connecting" and jm in (map_a, map_b):
        bad("the second join starts with the previous server's map %s" % jm, d)
for m in (map_a, map_b, map_c):
    if m not in seen_pics:
        bad("map %s never had its own picture" % m)
    elif len(seen_pics[m]) != 1:
        bad("map %s had several pictures %s" % (m, sorted(seen_pics[m])))
if len({next(iter(v)) for v in seen_pics.values() if len(v) == 1}) != len([v for v in seen_pics.values() if len(v) == 1]):
    bad("two maps shared one picture: %s" % seen_pics)
if not fails:
    print("ok   picture identity per map (%s, %s, %s) incl. a second join to another map" % (map_a, map_b, map_c))

# 2. screen-state sequence: from the first snapshot of a join until its first drawn world, the
#    app never reads `menu` (the main menu) and the menu key-dest never opens; the connect stage
#    never falls back to idle until the signon is complete.
def joins():
    """Yield the lists of events of each join: a join starts at a stage `connecting`, or at a
    `loading`/`preview` stage with signon 1 that follows an `idle` stage (a map change)."""
    cur, last_idle, last_stage = None, True, "idle"
    for topic, d in events:
        if topic == "connect":
            st = d.get("stage")
            starts = (st == "connecting" and last_stage != "connecting") or (st in ("loading", "preview", "downloading", "signon") and last_stage == "idle")
            if starts:
                if cur: yield cur
                cur = []
            last_stage = st
        if cur is not None:
            cur.append((topic, d))
    if cur: yield cur

n = 0
for j in joins():
    n += 1
    world = False
    state_menu = 0
    for topic, d in j:
        if topic == "connect":
            if d.get("world"): world = True
            if d.get("stage") == "idle" and d.get("signon", 0) < d.get("signons", 4) and d.get("status", "").startswith("Connect"):
                bad("join %d: stage went idle during the signon" % n, d)
        if topic == "app" and not world and d.get("state") != "ingame" and d.get("state") != "paused":
            if d.get("state") == "menu":
                bad("join %d: the main menu state shows during the join" % n, d)
            if d.get("menu"):
                bad("join %d: the menu is open before the first world frame" % n, d)
# 3. a lost connection (MARK-FREEZE): from the disconnect until the game is entered again, every app
#    snapshot that reads `menu` carries the reconnect text, so the native side can keep the join card
#    up (a bare `menu` would show the main menu in the gap).
frozen, regained = False, False
for topic, d in events:
    if topic == "mark" and d["name"] == "MARK-FREEZE":
        frozen = True
    elif frozen and topic == "app":
        if d.get("state") == "ingame":
            regained = True
        if not regained and d.get("state") == "menu" and not d.get("reconnect"):
            if d.get("reconnect_note", "").startswith("Disconnected"):
                continue
            bad("after the lost connection the app reads a bare menu (no reconnect text)", d)
if frozen and not regained:
    bad("the lost connection was never regained")
if n < 4:
    bad("expected 4 joins (connect, map change, second connect, reconnect after a lost connection), found %d" % n)
if not [f for f in fails if f.startswith("join")]:
    print("ok   join-card screen sequence: %d joins, no menu/idle before the first world frame" % n)

sys.exit(1 if fails else 0)
