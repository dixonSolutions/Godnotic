#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The server list's sort is the player's and is remembered (docs/UI_BRIDGE.md,
"Lists that stay put while they update"). Run by scripts/test-mpsort.sh.

  native   The engine as a native front end's backend (-uibridge listen,
           offscreen) with fake servers on loopback (tests/srvlist/fakenet.py):
           a header tap (server.sort) is the list's sort in every later build
           while a Refresh streams 200 servers in, after Back, and after the app
           quits and starts again (touch_mp_sort / touch_mp_sortdesc are saved in
           config.cfg; the prepared list and its first rows come sorted).
  quakec   The QuakeC renderer (the web front end), driven by uib_input like a
           mouse: a tap on the Players header sorts by players, most first, tells
           the menu VM (the same server.sort), a second tap turns it around, and
           the sort survives Back and a restart.
"""
import json, os, re, signal, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "tests", "uibridge"))
sys.path.insert(0, os.path.join(ROOT, "tests", "mpfast"))
os.environ.setdefault("UIB_BASEDIR", os.environ.get("BASEDIR", ""))
import engine_test as et  # Conn, spawn, connect
import mpfast_test as mf  # world, saved_list, Renderer

SERVERS = mf.SERVERS
fails = 0


def check(cond, what, detail=""):
    global fails
    print(("ok   " if cond else "FAIL ") + what + (f" ({detail})" if detail else ""))
    if not cond:
        fails += 1


def saved_cfg(ud):
    try:
        cfg = open(os.path.join(ud, "data", "config.cfg"), encoding="utf-8", errors="replace").read()
    except OSError:
        return {}
    return dict(re.findall(r'^seta "?(touch_mp_sort(?:desc)?)"? "([^"]*)"$', cfg, re.M))


def ordered(rows):
    keys = [(-r["cells"].get("players", 0), r["key"]) for r in rows]
    return keys == sorted(keys)


# ------------------------------------------------------------------ native renderer

def start_native(ud, port, sock):
    pre = ('sv_master1 "127.0.0.1:%d"\n' % port + "".join('%s ""\n' % m for m in ("sv_master2", "sv_master3", "sv_master4", "sv_masterextra1", "sv_masterextra2", "sv_masterextra3"))
           + 'set net_slist_favorites ""\nset net_slist_lan_repeats 0\nset net_slist_seed 0\nset touch_profile_done 1\n'
           'set menu_slist_showempty 1\nset menu_slist_showfull 1\nset menu_slist_showlaggy 1\n')
    if os.path.exists(sock):
        os.unlink(sock)
    p, log = et.spawn(["-uibridge", "listen:" + sock], ud, pre)
    c = mf.Renderer(et.connect(sock))
    c.send({"t": "hello", "schema": "1.0", "renderer": {"name": "mpsort_test"}, "caps": {"widthClass": "expanded", "nodes": ["screen", "list", "card"]}})
    ok = c.wait(lambda: c.rev >= 0 and (c.app().get("stack") or [""])[-1:] == ["home"], 60)
    return p, log, c, ok


def stack(c):
    return (c.app().get("stack") or [""])[-1:]


def lst(c):
    return c.nodes.get("mp/servers", {}).get("props", {})


def quit_engine(c, p):
    # Home's Quit, as the player quits (the renderer confirmed): the engine saves config.cfg
    c.send({"t": "ev", "ev": "action", "node": "home/nav/quit", "action": "quit"})
    try:
        p.wait(20)
        return True
    except subprocess.TimeoutExpired:
        p.kill()
        return False


def native(work):
    port = 27900 + os.getpid() % 40
    servers = mf.world(work, port)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tests", "srvlist", "fakenet.py"), "--state", os.path.join(work, "world.json"),
                             "--report", os.path.join(work, "report.json"), "--master-port", str(port), "--master-latency", "0.05"])
    ud = os.path.join(work, "ud")
    mf.saved_list(ud, servers)
    sock = os.path.join(work, "uib.sock")
    p = log = None
    try:
        p, log, c, ok = start_native(ud, port, sock)
        check(ok, "native: Home shows")
        check(lst(c).get("sort") == "ping" and lst(c).get("desc") is False, "native: a first start sorts by Ping", "%s %s" % (lst(c).get("sort"), lst(c).get("desc")))
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
        c.wait(lambda: stack(c) == ["mp"], 5)
        c.settle(0.3, 3)
        c.send({"t": "ev", "ev": "action", "node": "mp/servers", "action": "server.sort", "payload": {"sort": "players", "desc": True}})
        ok = c.wait(lambda: lst(c).get("sort") == "players" and lst(c).get("desc") is True, 2)
        check(ok, "native: the header tap's sort is the list's own")
        # a Refresh streams the 200 servers in: no build of the list undoes the sort, every window is in its order
        t1 = time.monotonic()
        c.send({"t": "ev", "ev": "action", "node": "mp/bar/filters/refresh", "action": "refresh"})
        end = time.monotonic() + 4
        while time.monotonic() < end:
            c.recv(0.05)
        builds = []
        for tt, t, m in c.log:
            if tt > t1 and t == "diff":
                builds += [(n["props"].get("sort"), n["props"].get("desc")) for n in m.get("set", []) if n["id"] == "mp/servers"]
        windows = [m for tt, t, m in c.log if tt > t1 and t == "rows" and m.get("source") == "servers"]
        check(len(builds) >= 1 and all(b == ("players", True) for b in builds), "native: no build of the list during the refresh undid the sort",
              "%d builds: %s" % (len(builds), builds[:4]))
        check(len(windows) >= 2 and all(ordered(w["rows"]) for w in windows), "native: every window streamed in is in the player's order",
              "%d windows, total %s" % (len(windows), c.rows.get("servers", {}).get("total")))
        c.send({"t": "ev", "ev": "back"})
        c.wait(lambda: stack(c) == ["home"], 5)
        c.settle(0.3, 2)
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
        c.wait(lambda: stack(c) == ["mp"], 5)
        c.settle(0.3, 2)
        check(lst(c).get("sort") == "players" and lst(c).get("desc") is True, "native: Back and Multiplayer again: still by Players, most first")
        c.send({"t": "ev", "ev": "back"})
        c.wait(lambda: stack(c) == ["home"], 5)
        quit_ok = quit_engine(c, p)
        saved = saved_cfg(ud)
        check(quit_ok and saved.get("touch_mp_sort") == "players" and saved.get("touch_mp_sortdesc") == "1",
              "native: Quit saves the sort (config.cfg)", str(saved))
        log.close()
        # the app starts again: the prepared list, and the first rows it gets unasked, are sorted the same
        c.rows.clear()
        p, log, c, ok = start_native(ud, port, sock)
        check(ok, "native: Home shows after the restart")
        ok = c.wait(lambda: "mp/servers" in c.nodes and c.rows.get("servers", {}).get("total", 0) > 0, 10)
        r = c.rows.get("servers", {})
        check(lst(c).get("sort") == "players" and lst(c).get("desc") is True, "native: after a restart Multiplayer is prepared by Players, most first",
              "%s %s" % (lst(c).get("sort"), lst(c).get("desc")))
        check(ok and r.get("re") == 0 and len(r.get("rows", [])) > 0 and ordered(r["rows"]) and r["rows"][0]["cells"].get("players", 0) > 0,
              "native: its first rows come in that order (no empty server on top)",
              "first: %s" % [(x["cells"].get("name"), x["cells"].get("players")) for x in r.get("rows", [])[:3]])
        # back to Ping, for the next run of the player (and so the cvar is shown to change back)
        c.send({"t": "ev", "ev": "action", "node": "mp/servers", "action": "server.sort", "payload": {"sort": "ping", "desc": False}})
        c.wait(lambda: lst(c).get("sort") == "ping", 2)
        # a sort the list has no column for is refused
        c.send({"t": "ev", "ev": "action", "node": "mp/servers", "action": "server.sort", "payload": {"sort": "bogus", "desc": True}})
        c.settle(0.3, 2)
        check(lst(c).get("sort") == "ping" and lst(c).get("desc") is False, "native: back to Ping; an unknown column is ignored")
        quit_engine(c, p)
        saved = saved_cfg(ud)
        check(saved.get("touch_mp_sort", "ping") == "ping" and saved.get("touch_mp_sortdesc", "0") == "0", "native: Ping saved again", str(saved))
    finally:
        if p and p.poll() is None:
            p.kill()
        fake.terminate()
        if log:
            log.close()


# ------------------------------------------------------------------ QuakeC renderer

def quakec_run(ud, steps, tag):
    """Runs the QuakeC renderer through `steps` (wait, command, name); returns {name: nodes}."""
    t = 6.0
    lines = []
    for wait, cmd, name in steps:
        lines.append(f'defer {t:.2f} "{cmd}"')
        t += wait
        lines.append(f'defer {t:.2f} "echo STEP {name}; uib_dump"')
        t += 0.4
    lines.append(f'defer {t + 0.5:.2f} "quit"')
    os.makedirs(os.path.join(ud, "data"), exist_ok=True)
    open(os.path.join(ud, "data", "mpsort-qc.cfg"), "w").write("\n".join(lines) + "\n")
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": ud, "SDL_VIDEODRIVER": "offscreen", "SDL_AUDIODRIVER": "dummy", "LANG": "C"}
    if os.environ.get("UIB_LIBDIR"):
        env["LD_LIBRARY_PATH"] = os.environ["UIB_LIBDIR"]
    cmd = ["timeout", str(int(t + 60)), et.ENGINE, "-xonotic", "-nosound", "-basedir", et.BASEDIR, "-userdir", ud,
           "+vid_width", "1280", "+vid_height", "720", "+vid_fullscreen", "0", "+vid_touchscreen", "0",
           "+set", "menu_mouse_absolute", "1", "+set", "touch_firstrun_done", "1", "+set", "touch_profile_done", "1", "+set", "_touch_assets_ready", "1",
           "+set", "net_slist_lan_only", "1", "+set", "cl_curl_enabled", "0", "+exec", "mpsort-qc.cfg"]
    if subprocess.run(["unshare", "-rn", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        cmd = ["unshare", "-rn"] + cmd
    out = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=et.BASEDIR).stdout.decode("utf-8", "replace")
    open(os.path.join(ud, "qc-%s.log" % tag), "w").write(out)
    state, cur = {}, None
    for line in out.splitlines():
        if line.startswith("STEP "):
            cur = line[5:].strip()
            state[cur] = {}
        elif cur and line.startswith('{"uib":1,"t":"snapshot"') and not state[cur]:
            try:
                state[cur] = {n["id"]: n for n in json.loads(line)["nodes"]}
            except ValueError:
                pass
    return state


def quakec(work):
    ud = os.path.join(work, "qc-ud")
    mf.saved_list(ud, mf.world(work, 1))
    def sort_of(state, step):
        p = state.get(step, {}).get("mp/servers", {}).get("props", {})
        return (p.get("sort"), p.get("desc"))
    def top(state, step):
        return (state.get(step, {}).get("app", {}).get("props", {}).get("stack") or [""])[-1:]
    s = quakec_run(ud, [(1.0, "echo", "start"),
                        (1.6, "uib_input tap home/tiles/mp", "open"),
                        (1.0, "uib_input tap mp/servers@players", "players"),
                        (1.0, "uib_input tap mp/servers@players", "players-again"),
                        (1.0, "uib_input tap mp/servers@players", "players-third"),
                        (1.0, "uib_input key ESCAPE", "back"),
                        (1.6, "uib_input tap home/tiles/mp", "reopen")], "first")
    check(len(s) == 7, "quakec: every step ran", "%d/7" % len(s))
    check(top(s, "open") == ["mp"] and sort_of(s, "open") == ("ping", False), "quakec: Multiplayer opens by Ping", str(sort_of(s, "open")))
    check(sort_of(s, "players") == ("players", True), "quakec: a tap on the Players header sorts by players, most first, and the menu VM keeps it", str(sort_of(s, "players")))
    check(sort_of(s, "players-again") == ("players", False), "quakec: a second tap turns it around", str(sort_of(s, "players-again")))
    check(sort_of(s, "players-third") == ("players", True), "quakec: a third tap back to most first", str(sort_of(s, "players-third")))
    check(top(s, "back") == ["home"] and top(s, "reopen") == ["mp"] and sort_of(s, "reopen") == ("players", True),
          "quakec: Back and Multiplayer again: still by Players", str(sort_of(s, "reopen")))
    saved = saved_cfg(ud)
    check(saved.get("touch_mp_sort") == "players" and saved.get("touch_mp_sortdesc") == "1", "quakec: saved in config.cfg", str(saved))
    s = quakec_run(ud, [(1.0, "echo", "start"), (1.6, "uib_input tap home/tiles/mp", "open")], "restart")
    check(top(s, "open") == ["mp"] and sort_of(s, "open") == ("players", True), "quakec: after a restart Multiplayer opens by Players", str(sort_of(s, "open")))


def main():
    work = tempfile.mkdtemp(prefix="godnotic-mpsort.", dir=os.environ.get("TMPDIR"))
    try:
        native(work)
        quakec(work)
    finally:
        if fails + et.fails == 0 and not os.environ.get("KEEP"):
            subprocess.call(["rm", "-rf", work])
        else:
            print("work dir:", work)
    total = fails + et.fails
    print("PASS" if not total else "FAIL: %d" % total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
