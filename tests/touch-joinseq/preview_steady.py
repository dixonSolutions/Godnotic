"""The join card's preview over a whole join (a log of `FE> connect {json}` snapshots, as bench-srvjoin.py or
scripts/test-touch-join-sequence.sh write it): from the first snapshot that names a picture until the game is
entered, the picture must never go blank, never change to another file and back, and never name another map.
A better picture of the same map may replace it once (list -> map kind), never the other way round.

  python3 -I preview_steady.py <log> [--allow-engine-gaps]

Without --allow-engine-gaps the ENGINE's snapshots themselves must be steady; with it, only what the card
shows must be: the card applies JoinFlow.nextShot (kept here as next_shot, tests/.../JoinCardTest.kt has the same
cases), which treats an empty picture as a gap. Prints one line per join: the sequence of pictures, and ok/FAIL.
"""
import json, os, sys

RANK = {"map": 3, "list": 2, "generic": 1, "": 0}


def next_shot(cur, inc):
    """Mirror of JoinFlow.nextShot: (path, kind, map) tuples."""
    if not inc[0]:
        return cur
    if not cur[0]:
        return inc
    if inc[0] == cur[0]:
        return inc if inc[1] != cur[1] else cur
    if inc[2] and cur[2] and inc[2] != cur[2]:
        return inc
    return inc if RANK.get(inc[1], 0) > RANK.get(cur[1], 0) else cur


def main():
    log = sys.argv[1]
    allow = "--allow-engine-gaps" in sys.argv
    started = False
    raw, card = [], []  # picture paths in order of change
    cur = ("", "", "")
    fails = []
    ingame = False
    n = 0
    for line in open(log, errors="replace"):
        i = line.find("FE> ")
        if i > 0 and line[:i].strip().replace(".", "").isdigit():
            line = line[i:]  # bench-srvjoin.py stamps each line
        if line.startswith("FE> app ") and '"state":"ingame"' in line:
            ingame = True
        if not line.startswith("FE> connect ") or ingame:
            continue
        try:
            d = json.loads(line[len("FE> connect "):])
        except ValueError:
            continue
        if d.get("stage") == "idle" and not started:
            continue
        shot, kind, jm = d.get("levelshot", ""), d.get("levelshot_kind", ""), d.get("join_map", "")
        n += 1
        if shot:
            started = True
        elif started and d.get("stage") != "idle" and not d.get("world"):
            raw.append("<blank>")
            if not allow:
                fails.append("engine snapshot %d has no picture (stage %s signon %s)" % (n, d.get("stage"), d.get("signon")))
        if shot and (not raw or raw[-1] != os.path.basename(shot) + ":" + kind):
            raw.append(os.path.basename(shot) + ":" + kind)
        new = next_shot(cur, (shot, kind, jm))
        if new != cur:
            card.append(os.path.basename(new[0]) + ":" + new[1])
            cur = new
    seq = [c for c in raw if c != "<blank>"]
    paths = []
    for c in seq:
        if not paths or paths[-1] != c.split(":")[0]:
            paths.append(c.split(":")[0])  # the same file with a better kind (list -> map) is not a change of file
    if len(set(paths)) != len(paths):
        fails.append("the picture went to another file and back: %s" % seq)
    ranks = [RANK.get(c.split(":")[1], 0) for c in seq]
    if ranks != sorted(ranks):
        fails.append("the picture got worse: %s" % seq)
    if len(card) > 2:
        fails.append("the card swapped its picture %d times: %s" % (len(card) - 1, card))
    note = ""
    if not card:
        # a custom map whose pack has no picture (Feris2, sinister_full_r1): the engine names none; the Android card shows
        # the art MapShots makes from the name from its first frame, so there is no blank there. Say so, do not fail.
        note = " (the engine names no picture: the card shows the generated art, MapShots.fallback)"
    print("%s engine=%s card=%s%s" % ("FAIL" if fails else "ok  ", raw, card, note))
    for f in fails:
        print("     ", f)
    return 1 if fails else 0


sys.exit(main())
