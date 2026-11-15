#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Multiplayer opens in the next frame, with rows (docs/UI_BRIDGE.md, "Opening a
screen fast"), against the real engine as a native front end's backend
(-uibridge listen, offscreen), a fake master and fake servers on loopback
(tests/srvlist/fakenet.py) and a saved list of them in the user directory.
Run by scripts/test-mpfast.sh.

  1 prepared  With Home shown, Multiplayer is already in the tree (app.prepared)
              with its list counted, and the engine has sent that list's first
              rows (a rows message with re 0), before any tap: a renderer has
              the screen and its rows ready. Before the first refresh the rows
              are the saved list's, marked stale; nothing was asked on the
              network for them.
  2 open      The tap's answer (the diff with Multiplayer on the stack) comes
              within the budget, and the renderer needs no window round trip:
              the list keeps its rows. The refresh then runs (the stats line
              settles) and the rows answer in place (stale goes false).
  3 back      Back to Home keeps Multiplayer prepared: none of its nodes is
              deleted, so the views a renderer keeps stay valid; the second
              open is as fast.
  4 fresh     A list that appears after the engine was idle on Home gets its
              count in the very diff that creates it and its first rows right
              after (Create's map picker, built only when opened).
  5 wake      The engine idles behind a native screen, yet answers a message
              at once (it sleeps on the bridge, not on the clock).

Budgets (BUDGET_MS, default 30 ms for the engine's answer to the tap) are for
this desktop under load; the tablet numbers are in docs/handoffs/mpfast.md.
"""
import json, os, signal, socket, statistics, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "tests", "uibridge"))
os.environ.setdefault("UIB_BASEDIR", os.environ.get("BASEDIR", ""))
import engine_test as et  # Conn, spawn, connect, check

BUDGET = float(os.environ.get("BUDGET_MS", "30"))
SERVERS = 200
fails = 0


def check(cond, what, detail=""):
    global fails
    print(("ok   " if cond else "FAIL ") + what + (f" ({detail})" if detail else ""))
    if not cond:
        fails += 1


def world(work, port):
    servers = []
    for i in range(SERVERS):
        servers.append({"ip": "127.79.%d.%d" % (i // 200, i % 200 + 1), "port": 26000, "alive": True, "latency_ms": 20 + (i * 13) % 600,
                        "humans": i % 5, "bots": 0, "max": 16, "map": "stormkeep", "name": "Fake %d" % i})
    with open(os.path.join(work, "world.json"), "w") as f:
        json.dump({"servers": servers, "hold_answers": 0}, f)
    return servers


def saved_list(ud, servers):
    now = int(time.time())
    os.makedirs(os.path.join(ud, "data"), exist_ok=True)
    with open(os.path.join(ud, "data", "touch_serverlist_info.txt"), "w") as f:
        f.write("godnotic-serverlist 1\n")
        for s in servers:
            # last session's numbers differ from today's: rows move as answers come in
            f.write("%s:%d\t%d\t%d\t0\t16\t3\t806\t%d\t0\tSaved %s\tstormkeep\tdata\tXonotic\tdm:0.8.6:P0:S16:F5:MXonotic::score!!\n"
                    % (s["ip"], s["port"], s["latency_ms"] + 7, (s["humans"] + 2) % 5, now - 60, s["name"]))


class Renderer(et.Conn):
    """A native renderer's view of the bridge: the tree, plus the rows each source has."""
    def __init__(self, sock):
        super().__init__(sock)
        self.rows = {}      # source -> {"rev", "total", "rows", "re", "at"}
        self.asked = set()  # sources a window was asked for
        self.log = []       # (time, message type, summary)

    def recv(self, timeout):
        m = super().recv(timeout)
        if m is None:
            return None
        now = time.monotonic()
        if m.get("t") == "rows":
            self.rows[m["source"]] = {"rev": m["rev"], "total": m["total"], "rows": m["rows"], "re": m.get("re", 0), "at": now}
        self.log.append((now, m.get("t"), m))
        # what the Android renderer does: a shown list with a count and no rows asks
        # for its window; a source notice re-asks (fe/ViewRenderer.kt, Lists.kt)
        top = (self.app().get("stack") or [""])[-1]
        for n in list(self.nodes.values()):
            if n["type"] != "list" or not n["id"].startswith(top + "/"):
                continue
            src = n["props"].get("source", "")
            known = self.rows.get(src)
            notice = m.get("t") == "source" and m.get("source") == src and (not known or known["rev"] < m["rev"])
            if ("count" in n["props"] and not known and src not in self.asked) or notice:
                self.asked.add(src)
                self.send({"t": "window", "source": src, "offset": 0, "limit": 50, "sort": n["props"].get("sort", ""),
                           "desc": bool(n["props"].get("desc")), "filter": n["props"].get("filter", "")})
        return m

    def app(self):
        return self.nodes.get("app", {}).get("props", {})

    def wait(self, cond, limit):
        end = time.monotonic() + limit
        while time.monotonic() < end:
            if cond():
                return True
            self.recv(0.05)
        return cond()


def main():
    work = tempfile.mkdtemp(prefix="godnotic-mpfast.")
    port = 27960 + os.getpid() % 30
    servers = world(work, port)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tests", "srvlist", "fakenet.py"), "--state", os.path.join(work, "world.json"),
                             "--report", os.path.join(work, "report.json"), "--master-port", str(port), "--master-latency", "0.05"])
    ud = os.path.join(work, "ud")
    saved_list(ud, servers)
    sock = os.path.join(work, "uib.sock")
    pre = ('sv_master1 "127.0.0.1:%d"\n' % port + "".join('%s ""\n' % m for m in ("sv_master2", "sv_master3", "sv_master4", "sv_masterextra1", "sv_masterextra2", "sv_masterextra3"))
           + 'set net_slist_favorites ""\nset net_slist_lan_repeats 0\nset net_slist_seed 0\nset net_slist_stats_print 1\nset uib_trace 1\nset touch_profile_done 1\nset cl_maxidlefps 20\n'
           'set menu_slist_showempty 1\nset menu_slist_showfull 1\nset menu_slist_showlaggy 1\n')
    p, log = et.spawn(["-uibridge", "listen:" + sock], ud, pre)
    try:
        c = Renderer(et.connect(sock))
        c.send({"t": "hello", "schema": "1.0", "renderer": {"name": "mpfast_test"}, "caps": {"widthClass": "expanded", "nodes": ["screen", "list", "card"]}})
        ok = c.wait(lambda: c.rev >= 0 and (c.app().get("stack") or [""])[-1:] == ["home"], 60)
        check(ok, "Home shows")

        # 1 prepared
        ok = c.wait(lambda: "mp" in (c.app().get("prepared") or []) and "mp/servers" in c.nodes, 10)
        check(ok, "prepared: Multiplayer is in the tree while Home shows (app.prepared)", str(c.app().get("prepared")))
        lst = c.nodes.get("mp/servers", {}).get("props", {})
        ok = c.wait(lambda: c.rows.get("servers", {}).get("total", 0) >= SERVERS, 5)
        r = c.rows.get("servers", {})
        check(int(c.nodes.get("mp/servers", {}).get("props", {}).get("count", 0)) >= SERVERS,
              "prepared: its list is counted in the tree", "count %s" % c.nodes.get("mp/servers", {}).get("props", {}).get("count"))
        check(ok and r.get("re") == 0 and len(r.get("rows", [])) >= min(50, SERVERS),
              "prepared: the engine sent the list's first rows unasked", "re %s, %d rows of %s" % (r.get("re"), len(r.get("rows", [])), r.get("total")))
        check(all(row.get("cells", {}).get("stale") for row in r.get("rows", [])) and r.get("rows", [{}])[0].get("cells", {}).get("name", "").startswith("Saved"),
              "prepared: before the first refresh they are the saved rows, stale")
        rep = json.load(open(os.path.join(work, "report.json"))) if os.path.exists(os.path.join(work, "report.json")) else {}
        fake.send_signal(signal.SIGUSR1); time.sleep(0.3)
        rep = json.load(open(os.path.join(work, "report.json")))
        check(rep.get("queries", 0) == 0 and rep.get("master_queries", 0) == 0, "prepared: nothing was asked on the network for them",
              "%s queries, %s master queries" % (rep.get("queries"), rep.get("master_queries")))

        # 2 open: idle on Home first, as a player who looks at it a while
        time.sleep(2.0)
        opens = []
        for k in range(2):
            c.settle(0.4, 3)
            t0 = time.monotonic()
            had = c.rows.get("servers", {}).get("total", 0) >= SERVERS
            c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
            ok = c.wait(lambda: (c.app().get("stack") or [""])[-1:] == ["mp"], 5)
            dt = (time.monotonic() - t0) * 1000
            ok_rows = c.wait(lambda: c.rows.get("servers", {}).get("total", 0) > 0, 5)
            dr = max(dt, (c.rows.get("servers", {}).get("at", t0) - t0) * 1000)
            opens.append((dt, dr))
            check(ok and dt < BUDGET, "open %d: the engine's answer to the tap within %.0f ms" % (k + 1, BUDGET), "%.1f ms" % dt)
            # the renderer already holds the rows: nothing to ask for before drawing
            check(had and ok_rows and dr < BUDGET, "open %d: the list has its rows at the tap, without a window round trip" % (k + 1),
                  "screen %.1f ms, rows %.1f ms after the tap" % (dt, dr))
            ok = c.wait(lambda: any(t == "rows" and m.get("re") == 0 for tt, t, m in c.log if tt > t0) or True, 0.2)
            # the refresh runs and answers in place
            fresh = c.wait(lambda: not any(row.get("cells", {}).get("stale") for row in c.rows.get("servers", {}).get("rows", [{"cells": {"stale": True}}])), 8)
            if not fresh:
                c.request({"t": "window", "source": "servers", "offset": 0, "limit": 50, "sort": "ping"})
                fresh = not any(row.get("cells", {}).get("stale") for row in c.rows.get("servers", {}).get("rows", [{"cells": {"stale": True}}]))
            check(fresh, "open %d: the refresh answers the rows in place (stale goes false)" % (k + 1))
            check(c.rows.get("servers", {}).get("total", 0) >= SERVERS - 2, "open %d: every server listed" % (k + 1), str(c.rows.get("servers", {}).get("total")))
            # 3 back
            mpnodes = {i for i in c.nodes if i == "mp" or i.startswith("mp/")}
            t1 = time.monotonic()
            c.send({"t": "ev", "ev": "back"})
            c.wait(lambda: (c.app().get("stack") or [""])[-1:] == ["home"], 5)
            c.settle(0.3, 2)
            deleted = set()
            for tt, t, m in c.log:
                if tt >= t1 and t == "diff":
                    deleted |= set(m.get("del", []))
            check(not (deleted & mpnodes) and "mp" in (c.app().get("prepared") or []),
                  "back %d: Multiplayer stays prepared, none of its nodes deleted" % (k + 1), ", ".join(sorted(deleted & mpnodes))[:120])
            time.sleep(1.5)
        print("open: tap to screen / rows (ms): %s" % ", ".join("%.1f / %.1f" % o for o in opens))

        # 6 stable: the player sorts by Players and turns Empty and Laggy off, then a
        # full Refresh streams 200 answers in: every window the renderer gets is
        # filtered and in the player's order, ties in one fixed order, and the sort
        # is still the player's in every later build of the list
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
        c.wait(lambda: (c.app().get("stack") or [""])[-1:] == ["mp"], 5)
        c.settle(0.3, 3)
        c.send({"t": "ev", "ev": "action", "node": "mp/servers", "action": "server.sort", "payload": {"sort": "players", "desc": True}})
        ok = c.wait(lambda: c.nodes.get("mp/servers", {}).get("props", {}).get("sort") == "players", 2)
        check(ok and c.nodes["mp/servers"]["props"].get("desc") is True, "stable: the sort a header tap asked for is the list's own")
        total0 = c.rows.get("servers", {}).get("total", 0)
        t0 = time.monotonic()
        c.send({"t": "ev", "ev": "value", "node": "mp/bar/filters/empty", "value": False})
        ok = c.wait(lambda: any(t == "source" and tt > t0 and m.get("total", total0) < total0 for tt, t, m in c.log), 2)
        tf = min([tt for tt, t, m in c.log if t == "source" and tt > t0] or [time.monotonic()])
        check(ok and (tf - t0) * 1000 < BUDGET, "stable: Empty off applies at once", "%.1f ms, %s of %s rows" % ((tf - t0) * 1000, c.rows.get("servers", {}).get("total"), total0))
        c.send({"t": "ev", "ev": "value", "node": "mp/bar/filters/laggy", "value": False})
        c.settle(0.3, 2)
        t1 = time.monotonic()
        c.send({"t": "ev", "ev": "action", "node": "mp/bar/filters/refresh", "action": "refresh"})
        end = time.monotonic() + 4
        while time.monotonic() < end:
            c.recv(0.05)
        windows = [m for tt, t, m in c.log if tt > t1 and t == "rows" and m.get("source") == "servers" and m.get("offset") == 0]
        sorts = [m["props"].get("sort") for tt, t, m in c.log if tt > t1 and t == "diff" for m in m.get("set", []) if m["id"] == "mp/servers"]
        bad_filter = bad_order = 0
        moved = 0
        prev = None
        for w in windows:
            rows = w["rows"]
            if any(r["cells"].get("players", 0) - r["cells"].get("bots", 0) <= 0 or r["cells"].get("ping", 0) >= 150 for r in rows):
                bad_filter += 1
            keys = [(-r["cells"].get("players", 0), r["key"]) for r in rows]
            if keys != sorted(keys):
                bad_order += 1
            if prev:
                # rows whose numbers did not change keep their order relative to each other
                same = [r["key"] for r in rows if r["key"] in prev and prev[r["key"]] == r["cells"].get("players")]
                order_before = [k for k in prev_order if k in set(same)]
                moved += same != order_before
            prev = {r["key"]: r["cells"].get("players") for r in rows}
            prev_order = [r["key"] for r in rows]
        check(len(windows) >= 2, "stable: the refresh streamed its rows in several updates", "%d windows" % len(windows))
        check(bad_filter == 0, "stable: every window during the refresh is filtered (no empty, no laggy server)", "%d bad of %d" % (bad_filter, len(windows)))
        check(bad_order == 0, "stable: every window is in the player's order, ties by address", "%d bad of %d" % (bad_order, len(windows)))
        check(moved == 0, "stable: rows that did not change keep their order across updates", "%d windows reordered unchanged rows" % moved)
        sorts.append(c.nodes.get("mp/servers", {}).get("props", {}).get("sort"))
        check(all(x == "players" for x in sorts), "stable: no build of the list undid the player's sort", "%d builds: %s" % (len(sorts), sorts[:6]))
        for node in ("empty", "laggy"):
            c.send({"t": "ev", "ev": "value", "node": "mp/bar/filters/" + node, "value": True})
        c.send({"t": "ev", "ev": "action", "node": "mp/servers", "action": "server.sort", "payload": {"sort": "ping", "desc": False}})
        c.send({"t": "ev", "ev": "back"})
        c.settle(0.3, 2)

        # 4 fresh: Create (map picker) is built only when opened
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
        c.wait(lambda: (c.app().get("stack") or [""])[-1:] == ["mp"], 5)
        c.settle(0.3, 2)
        t0 = time.monotonic()
        c.send({"t": "ev", "ev": "action", "node": "mp/join/create", "action": "create"})
        created = None
        end = time.monotonic() + 5
        while time.monotonic() < end and created is None:
            m = c.recv(0.1)
            if m and m.get("t") == "diff":
                for n in m.get("set", []):
                    if n["type"] == "list" and n["id"].startswith("sp/") and "maps" in str(n["props"].get("source", "")):
                        created = n
        check(created is not None and int(created["props"].get("count", 0)) > 0, "fresh: a list that appears is counted in the diff that creates it",
              created and "%s count %s" % (created["id"], created["props"].get("count")))
        if created:
            src = created["props"]["source"]
            ok = c.wait(lambda: c.rows.get(src, {}).get("re") == 0 and c.rows[src]["at"] >= t0, 1)
            check(ok, "fresh: its first rows follow unasked", "%.1f ms after the tap" % ((c.rows.get(src, {}).get("at", t0) - t0) * 1000))
        c.send({"t": "ev", "ev": "back"}); c.send({"t": "ev", "ev": "back"})
        c.settle(0.3, 2)

        # 5 wake: idle behind the native screen (cl_maxidlefps 20), answers at once
        time.sleep(1.0)
        lat = []
        for k in range(8):
            t0 = time.monotonic()
            r = c.request({"t": "ping"}, 2)
            lat.append((time.monotonic() - t0) * 1000)
            time.sleep(0.137)
        med = statistics.median(lat)
        check(med < 5, "wake: an idle engine answers within a few ms (not at its next 50 ms frame)", "median %.1f ms, max %.1f" % (med, max(lat)))
        c.send({"t": "bye", "reason": "test"})
    finally:
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            p.kill()
        fake.terminate()
        log.close()
        if os.environ.get("OUT"):
            os.makedirs(os.environ["OUT"], exist_ok=True)
            subprocess.call(["cp", os.path.join(ud, "engine.log"), os.environ["OUT"]])
        if fails + et.fails == 0 and not os.environ.get("KEEP"):
            subprocess.call(["rm", "-rf", work])
        else:
            print("work dir:", work)
    total = fails + et.fails  # schema checks on every snapshot and diff count too
    print("PASS" if not total else "FAIL: %d" % total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
