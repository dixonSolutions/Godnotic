#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Server control, bots, through the real menu QuakeC and a real local game.

A local game starts with ONE bot (the slots used to be bots + 1 = 2, so Server control could
never add a second: maxplayers cannot change while a match runs). This starts a local
Deathmatch with one bot, opens Server control from the pause page, sets 6 bots / Hard / Red
team in the controls, taps Apply bots and counts the bots on the server afterwards.

  UIB_BASEDIR=<basedir with game data and this clone's pk3dir> [UIB_ENGINE=...darkplaces-sdl] \
  [UIB_LIBDIR=...] python3 tests/botctl/test_ctl_ui.py
"""
import os, re, socket, sys, tempfile, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "uibridge"))
import engine_test as et   # the bridge harness (Conn, spawn, connect, hello, wait_for)

fails = 0
def check(cond, what):
    global fails
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        fails += 1

def status_unused(port):
    """Player lines of getstatus: (count, names)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(1.0)
    try:
        s.sendto(b"\xff\xff\xff\xffgetstatus", ("127.0.0.1", port))
        data = s.recv(65535).decode(errors="replace")
    except OSError:
        return None
    return [l for l in data.split("\n")[2:] if l.strip()]

LOG = [None]
def touchbots_lines():
    try:
        text = open(LOG[0], errors="replace").read()
    except OSError:
        return []
    return [tuple(int(v) for v in m) for m in re.findall(r"touchbots: want=(\d+) bots=(\d+) full=(\d) moved=(\d+) red=(\d+) blue=(\d+)", text)]

def bots_connected():
    """Bots now on the server, from the log: connects minus drops."""
    try:
        text = open(LOG[0], errors="replace").read()
    except OSError:
        return None
    return len(re.findall(r"\[BOT\][^\n]*connected", text)) - len(re.findall(r"\[BOT\][^\n]*disconnected", text))

def main():
    ud = tempfile.mkdtemp(prefix="botctl-", dir=os.environ.get("TMPDIR"))
    sock = os.path.join(ud, "run", "uib.sock")
    port = 26000 + 700 + os.getpid() % 200
    pre = ("set touch_lan_bots 1\nset touch_lan_gametype dm\nset touch_lan_map afterslime\nset g_warmup 0\n"
           "set touch_profile_done 1\nset cl_curl_enabled 0\nport %d\nset sv_public 0\n" % port)
    p, log = et.spawn(["-uibridge", "listen:" + sock], ud, pre)
    LOG[0] = os.path.join(ud, "engine.log")
    try:
        c = et.Conn(et.connect(sock)); et.hello(c); c.settle()
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/sp", "action": "sp"}); c.settle()
        c.send({"t": "ev", "ev": "action", "node": "sp/modes/grid/dm", "action": "mode.dm"}); c.settle()
        c.send({"t": "ev", "ev": "action", "node": "sp", "action": "play"})
        ok = et.wait_for(c, lambda: c.nodes.get("app", {}).get("props", {}).get("context") == "game" and "pause/actions/control" in c.nodes, 90)
        check(ok, "a local game is running with Server control on its pause page")
        c.settle(quiet=2, limit=8)
        n0 = None
        for _ in range(30):
            n0 = bots_connected()
            if n0: break
            time.sleep(1)
        check(n0 == 1, f"the local game starts with 1 bot ({n0})")
        c.send({"t": "ev", "ev": "action", "node": "pause/actions/control", "action": "ctl.open"}); c.settle()
        n = c.nodes.get("ctl/bots/botform/n", {}).get("props", {})
        check(n and n.get("max") == 32, f"the bots stepper goes to 32 in a local game ({n.get('max')})")
        for k in ("ctl/bots/botform/skill", "ctl/bots/botform/team", "ctl/match/limits/time", "ctl/match/limits/frags", "ctl/bots/apply"):
            check(k in c.nodes, f"control has {k}")
        c.send({"t": "ev", "ev": "value", "node": "ctl/bots/botform/n", "value": 6}); c.settle()
        c.send({"t": "ev", "ev": "value", "node": "ctl/bots/botform/skill", "value": "7"}); c.settle()
        c.send({"t": "ev", "ev": "value", "node": "ctl/bots/botform/team", "value": "red"}); c.settle()
        c.send({"t": "ev", "ev": "action", "node": "ctl/bots/apply", "action": "ctl.bots"}); c.settle(quiet=1.5)
        n1 = None
        for _ in range(15):
            n1 = bots_connected()
            if n1 == 6: break
            time.sleep(1)
        check(n1 == 6, f"6 bots selected and applied: 6 bots in the match, not 2 ({n1})")
        # fewer again, and the remembered values are the ones set
        c.send({"t": "ev", "ev": "value", "node": "ctl/bots/botform/n", "value": 3}); c.settle()
        c.send({"t": "ev", "ev": "action", "node": "ctl/bots/apply", "action": "ctl.bots"}); c.settle(quiet=1.5)
        n2 = None
        for _ in range(15):
            tb = touchbots_lines()
            n2 = tb[-1][1] if tb else None
            if n2 == 3: break
            time.sleep(1)
        check(n2 == 3, f"applying 3 afterwards leaves 3 bots ({n2})")
        log.flush()
        text = open(os.path.join(ud, "engine.log"), errors="replace").read()
        m = re.findall(r"touchbots: want=(\d+) bots=(\d+) full=(\d) moved=(\d+) red=(\d+) blue=(\d+)", text)
        check(any(x[0] == "6" and x[1] == "6" and x[2] == "0" for x in m), f"sv_cmd touchbots 6 reached 6 bots at once ({m[:3]})")
    finally:
        if p.poll() is None:
            p.kill(); p.wait()
        log.close()
    print("botctl-ui:", "FAILED" if fails else "all passed")
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(main())
