"""Assertions over a client log of a join whose server sends its own csprogs.dat (scripts/test-touch-join-csqc-download.sh).

  python3 -I csqc_check.py <client.log>

The `FE> connect {json}` snapshots while "Downloading new CSQC code" runs must carry that download:
downloads 1, dl_file csprogs.dat, a byte count that grows, a total, a stage that is not the signon wait
(wait_s -1), and the one bar's inputs (dl_all_bytes / dl_all_total).
"""
import json, sys

log = sys.argv[1]
snaps, started, finished = [], False, False
for line in open(log, errors="replace"):
    if "Downloading new CSQC code" in line:
        started = True
    elif started and "Downloaded \"dlcache/csprogs.dat" in line:
        finished = True
    elif line.startswith("FE> connect ") and started and not finished:
        try:
            snaps.append(json.loads(line[len("FE> connect "):]))
        except ValueError:
            pass
fails = []
def check(ok, what, *extra):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)
        for e in extra[:2]:
            print("    ", json.dumps(e)[:300])

check(any(l.startswith("MET-CVARS 0 256") for l in open(log, errors="replace")), "touch_net_metered (off) and touch_net_metered_shot_kb (256) exist for the app to set")
check(started, "the client downloads the server's csprogs.dat (it differs from ours)")
dl = [s for s in snaps if s.get("dl_file") == "csprogs.dat"]
check(len(dl) >= 3, "the connect topic names the download (csprogs.dat) in %d snapshots" % len(dl))
if dl:
    check(all(s["downloads"] == 1 and s["dl_running"] == 1 for s in dl), "it counts as one running download", dl[0])
    check(all(s["wait_s"] == -1 for s in dl), "the card is not told 'receiving game data' during it", dl[0])
    check(all(s["dl_all_total"] > 100000 for s in dl), "the total is known (%d bytes)" % dl[-1]["dl_all_total"], dl[0])
    got = [s["dl_all_bytes"] for s in dl]
    check(got[-1] > got[0] and got == sorted(got), "bytes received only grow: %d -> %d" % (got[0], got[-1]), dl[0], dl[-1])
    check(any(s["speed"] > 1000 for s in dl), "a speed is reported (max %d B/s)" % max(s["speed"] for s in dl), dl[-1])
    check(dl[-1]["dl_all_bytes"] <= dl[-1]["dl_all_total"] * 1.001, "never more than the total", dl[-1])
sys.exit(1 if fails else 0)
