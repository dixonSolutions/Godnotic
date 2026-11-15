#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# gtk_world.py <dir> [n]: world.json for tests/srvlist/fakenet.py (n servers, players 0..15, many empty) and a saved list
import json, os, sys, time
d = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 200
servers = []
for i in range(n):
    humans = (i * 7) % 16 if i % 3 == 0 else 0   # two in three servers are empty, like the real list
    servers.append({"ip": "127.79.%d.%d" % (i // 200, i % 200 + 1), "port": 26000, "alive": True, "latency_ms": 20 + (i * 13) % 400,
                    "humans": humans, "bots": 0, "max": 16, "map": "stormkeep", "name": "Fake %03d" % i})
json.dump({"servers": servers, "hold_answers": 0}, open(os.path.join(d, "world.json"), "w"))
ud = os.path.join(d, "ud", "data"); os.makedirs(ud, exist_ok=True)
now = int(time.time())
with open(os.path.join(ud, "touch_serverlist_info.txt"), "w") as f:
    f.write("godnotic-serverlist 1\n")
    for s in servers:
        f.write("%s:%d\t%d\t%d\t0\t16\t3\t806\t%d\t0\t%s\tstormkeep\tdata\tXonotic\tdm:0.8.6:P0:S16:F5:MXonotic::score!!\n"
                % (s["ip"], s["port"], s["latency_ms"] + 7, s["humans"], now - 60, s["name"]))
