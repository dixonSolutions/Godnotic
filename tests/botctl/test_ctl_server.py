#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Server control's bot commands against a real dedicated server (rcon over UDP).

  - the cause of "only one bot": a server with maxplayers 2 (what a local game started with
    one bot used to get) stops at that one bot whatever bot_number says
  - a server with the 33 slots a local game has now gets all N bots
  - `sv_cmd touchbots N [team]` (what Server control sends after bot_number): N bots at
    once, the added ones on the chosen team in a team game, balanced otherwise

  UIB_BASEDIR=<basedir with game data and this clone's pk3dir> [UIB_ENGINE_SV=.../darkplaces-dedicated] \
  [UIB_LIBDIR=...] python3 tests/botctl/test_ctl_server.py
"""
import os, re, socket, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASEDIR = os.environ["UIB_BASEDIR"]
SV = os.environ.get("UIB_ENGINE_SV", os.path.join(ROOT, "engine", "darkplaces", "darkplaces-dedicated"))
LIB = os.environ.get("UIB_LIBDIR", "")
fails = 0

def check(cond, what):
    global fails
    print(("ok   " if cond else "FAIL ") + what)
    fails += 0 if cond else 1

def rcon(port, cmd, wait=1.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(0.3)
    s.sendto(b"\xff\xff\xff\xffrcon botpw " + cmd.encode(), ("127.0.0.1", port))
    out, end = b"", time.time() + wait
    while time.time() < end:
        try: out += s.recv(65535)[4:]
        except socket.timeout: pass
    return out.decode(errors="replace")

def players(port):
    for l in rcon(port, "status").splitlines():
        m = re.match(r"players:\s+(\d+) active", l)
        if m: return int(m.group(1))
    return None

def server(port, slots, ud):
    os.makedirs(os.path.join(ud, "data"), exist_ok=True)
    with open(os.path.join(ud, "data", "bc.cfg"), "w") as f:
        f.write(f"rcon_secure 0\nrcon_password botpw\nsv_public 0\nport {port}\nmaxplayers {slots}\nbot_join_empty 1\nbot_number 0\nsetinfo_dummy 1\n")
    env = dict(os.environ)
    if LIB: env["LD_LIBRARY_PATH"] = LIB
    p = subprocess.Popen([SV, "-xonotic", "-basedir", BASEDIR, "-userdir", ud, "+exec", "bc.cfg", "+map", "dance"],
                         stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, env=env)
    for _ in range(90):
        if players(port) is not None: break
        time.sleep(1)
    time.sleep(3)   # the game's first seconds: bots are not counted before 2.5 s
    return p

CLIENT = os.environ.get("UIB_ENGINE", os.path.join(ROOT, "engine", "darkplaces", "darkplaces-sdl"))

def human(port, ud):
    """One real player: the stock bot_number only fills a server that has somebody in it."""
    env = dict(os.environ, SDL_VIDEODRIVER="offscreen", SDL_AUDIODRIVER="dummy")
    if LIB: env["LD_LIBRARY_PATH"] = LIB
    cud = tempfile.mkdtemp(prefix="botctl-cl-", dir=os.environ.get("TMPDIR"))
    os.makedirs(os.path.join(cud, "data"), exist_ok=True)
    c = subprocess.Popen([CLIENT, "-xonotic", "-basedir", BASEDIR, "-userdir", cud, "-nosound", "+vid_fullscreen", "0",
                          "+vid_width", "320", "+vid_height", "200", "+set", "_touch_assets_ready", "1",
                          "+connect", f"127.0.0.1:{port}"], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, env=env)
    for _ in range(60):
        if players(port) == 1: break
        time.sleep(1)
    time.sleep(3)
    return c

def settle_count(port, want, limit=8):
    """Bots on the server (players minus the one human), once it is `want` or the time is up."""
    n = None
    for _ in range(limit):
        n = players(port)
        n = None if n is None else n - 1
        if n == want: break
        time.sleep(1)
    return n

def main():
    base = 27100 + os.getpid() % 300
    # 1. two slots (player + one bot): the old local game
    ud = tempfile.mkdtemp(prefix="botctl-sv2-", dir=os.environ.get("TMPDIR"))
    p = server(base, 2, ud)
    cl = human(base, ud)
    try:
        rcon(base, "bot_number 5")
        n = settle_count(base, 5, 6)
        check(n == 1, f"maxplayers 2 (you + 1 bot): bot_number 5 still leaves 1 bot, the server is full ({n})")
        rcon(base, "sv_cmd touchbots 5")
        check(settle_count(base, 5, 4) == 1, "maxplayers 2: touchbots 5 cannot add more either")
    finally:
        cl.kill(); cl.wait(); p.kill(); p.wait()
    # 2. the 33 slots of a local game now
    ud = tempfile.mkdtemp(prefix="botctl-sv33-", dir=os.environ.get("TMPDIR"))
    p = server(base + 1, 33, ud)
    cl = human(base + 1, ud)
    try:
        port = base + 1
        rcon(port, "bot_number 6")
        n = settle_count(port, 6)
        check(n == 6, f"maxplayers 33: bot_number 6 gives 6 bots ({n})")
        rcon(port, "bot_number 20")
        n = settle_count(port, 20)
        check(n == 20, f"maxplayers 33: bot_number 20 gives 20 bots ({n})")
        rcon(port, "sv_cmd touchbots 0"); settle_count(port, 0)
        out = rcon(port, "sv_cmd touchbots 8 red", wait=2)
        m = re.search(r"touchbots: want=(\d+) bots=(\d+) full=(\d) moved=(\d+) red=(\d+) blue=(\d+)", out)
        check(m and m.group(2) == "8" and m.group(5) == "8" and m.group(6) == "0", f"touchbots 8 red: 8 bots, all red ({m and m.group(0)})")
        check(settle_count(port, 8) == 8, "the server has the 8 bots")
        out = rcon(port, "sv_cmd touchbots 0", wait=1.5); settle_count(port, 0)
        rcon(port, "sv_cmd touchbots 6 auto", wait=2); time.sleep(3)   # bots take their team a moment after they connect
        out = rcon(port, "sv_cmd touchbots 6 auto", wait=2)
        m = re.search(r"touchbots: want=(\d+) bots=(\d+) full=(\d) moved=(\d+) red=(\d+) blue=(\d+)", out)
        check(m and m.group(2) == "6" and abs(int(m.group(5)) - int(m.group(6))) <= 1, f"touchbots 6 auto: 6 bots balanced over the teams ({m and m.group(0)})")
        out = rcon(port, "sv_cmd touchbots 10 blue", wait=2)
        m = re.search(r"touchbots: want=(\d+) bots=(\d+) full=(\d) moved=(\d+) red=(\d+) blue=(\d+)", out)
        check(m and m.group(2) == "10" and m.group(4) == "4" and int(m.group(6)) >= 4, f"touchbots 10 blue: the 4 added bots went blue ({m and m.group(0)})")
    finally:
        cl.kill(); cl.wait(); p.kill(); p.wait()
    print("botctl-server:", "FAILED" if fails else "all passed")
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(main())
