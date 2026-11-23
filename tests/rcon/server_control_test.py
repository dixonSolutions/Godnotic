#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Server control, end to end (scripts/test-server-control.sh).

A dedicated server with a known rcon_password and a headless client driven over the UI bridge, the way
the in-game menu's "Server control" dialog is used:

  - the right password unlocks it (rcon_secure 0, 1 and 2) and the controls show
  - a wrong password keeps the dialog open with "Wrong password" and the controls stay hidden
  - a server that does not answer (stopped) gives "Server did not answer" and nothing unlocks
  - an unlocked dialog can send a command (bot_number) and the server obeys it
  - a game this engine hosts (a local match) opens the controls with no password and no rcon at all

The password is a test value (below); it is never a real one.
"""
import os, signal, socket, subprocess, sys, tempfile, time, importlib.util

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
os.environ.setdefault("UIB_BASEDIR", os.environ.get("BASEDIR", ""))
spec = importlib.util.spec_from_file_location("engine_test", os.path.join(ROOT, "tests", "uibridge", "engine_test.py"))
et = importlib.util.module_from_spec(spec)
spec.loader.exec_module(et)

SV = os.environ.get("SV_ENGINE", os.path.join(ROOT, "engine", "darkplaces", "darkplaces-dedicated"))
BASEDIR = et.BASEDIR
TESTPW = "srvctl-test-pw"
MAP = os.environ.get("SRVCTL_MAP", "afterslime")
check = et.check


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def start_server(port, secure, ud):
    os.makedirs(os.path.join(ud, "data"), exist_ok=True)
    with open(os.path.join(ud, "data", "sv.cfg"), "w") as f:
        f.write(f"sv_public -1\nmaxplayers 4\nbot_number 0\n"
                f"g_warmup 0\nskill 1\nport {port}\nnet_address 127.0.0.1\nmap {MAP}\n")
    # the stock config sets rcon_secure 1 while it loads, so the test's value goes in post-config.cfg (the last thing
    # a server executes); rcon_secure first, changing it clears rcon_password
    with open(os.path.join(ud, "data", "post-config.cfg"), "w") as f:
        f.write(f"rcon_secure {secure}\nrcon_password {TESTPW}\n")
    env = dict(os.environ)
    env.pop("DISPLAY", None)
    log = open(os.path.join(ud, "server.log"), "w")
    p = subprocess.Popen([SV, "-xonotic", "-basedir", BASEDIR, "-userdir", ud, "+exec", "sv.cfg"],
                         env=env, stdout=log, stderr=subprocess.STDOUT, cwd=BASEDIR)
    end = time.monotonic() + 90
    while time.monotonic() < end:  # the server is up once its log says the map is loaded
        time.sleep(0.5)
        try:
            if "Game is" in open(os.path.join(ud, "server.log"), errors="replace").read() or \
               "spawn" in open(os.path.join(ud, "server.log"), errors="replace").read().lower():
                break
        except OSError:
            pass
    time.sleep(2)
    return p, log


def props(c, node):
    return c.nodes.get(node, {}).get("props", {})


def app(c):
    return props(c, "app")


def verdicts(ud):
    """The menu logs each verdict on a typed password ("Server control: password ok (addr)")."""
    try:
        t = open(os.path.join(ud, "engine.log"), errors="replace").read()
    except OSError:
        return []
    return [ln.split("password ", 1)[1].split(" ", 1)[0] for ln in t.splitlines() if "Server control: password " in ln]


def in_game(c):
    return app(c).get("context") == "game" and "pause/actions/control" in c.nodes


def wait(c, cond, limit):
    return et.wait_for(c, cond, limit)


def client(ud, pre, args=()):
    sock = os.path.join(ud, "run", "uib.sock")
    p, log = et.spawn(["-uibridge", "listen:" + sock] + list(args), ud, pre)
    s = et.connect(sock)
    c = et.Conn(s)
    et.hello(c)
    return p, log, c


def open_control(c):
    """The pause menu's Server control tile, as a tap on it does."""
    c.send({"t": "ev", "ev": "action", "node": "pause/actions/control", "action": "ctl.open"})
    return wait(c, lambda: "ctl" in c.nodes and "ctl" in (app(c).get("modal") or []), 15)


def unlocked(c):
    return "ctl/bots/n" in c.nodes


def asks_password(c):
    return "ctl/form/pw" in c.nodes


def hint(c):
    return props(c, "ctl/hint").get("body", "")


def type_and_unlock(c, pw):
    c.send({"t": "ev", "ev": "value", "node": "ctl/form/pw", "value": pw})
    c.settle(quiet=0.3, limit=2)
    c.send({"t": "ev", "ev": "action", "node": "ctl/form", "action": "ctl.auth"})


def remote_scenario(secure, client_secure=None):
    tag = f"server rcon_secure {secure}" + ("" if client_secure is None else f", client {client_secure}")
    base = tempfile.mkdtemp(prefix="srvctl-", dir=os.environ.get("TMPDIR"))
    port = free_port()
    sp, slog = start_server(port, secure, os.path.join(base, "sv"))
    cud = os.path.join(base, "cl")
    pre = ("" if client_secure is None else f"set rcon_secure {client_secure}\n") + f"defer 3 \"connect 127.0.0.1:{port}\"\n"
    cp, clog, c = client(cud, pre)
    try:
        joined = wait(c, lambda: in_game(c), 90)
        check(joined, f"[{tag}] the client joined the server (context {app(c).get('context')!r})")
        if not joined:
            return
        c.settle(quiet=1.0, limit=5)
        # online: the password form first, nothing that acts on the server
        check(open_control(c) and asks_password(c) and not unlocked(c), f"[{tag}] Server control asks for the password on another's server")

        # wrong password: stays locked, says so
        type_and_unlock(c, "not-the-password")
        ok = wait(c, lambda: hint(c) == "Wrong password", 12)
        check(ok and asks_password(c) and not unlocked(c), f"[{tag}] a wrong password keeps the dialog open with an inline error ({hint(c)!r})")
        check(verdicts(cud)[-1:] == ["denied"], f"[{tag}] the menu logged the refusal ({verdicts(cud)})")

        # the right password
        type_and_unlock(c, TESTPW)
        ok = wait(c, lambda: unlocked(c), 12)
        check(ok and not asks_password(c), f"[{tag}] the right password unlocks it")
        check(verdicts(cud)[-1:] == ["ok"], f"[{tag}] the menu logged the acceptance ({verdicts(cud)})")

        # and the unlocked dialog really reaches the server
        if ok:
            c.send({"t": "ev", "ev": "value", "node": "ctl/bots/n", "value": 2})
            c.settle(quiet=0.3, limit=2)
            c.send({"t": "ev", "ev": "action", "node": "ctl/bots/apply", "action": "ctl.bots"})
            time.sleep(4)
            log = open(os.path.join(base, "sv", "server.log"), errors="replace").read()
            check("[BOT]" in log, f"[{tag}] a command sent from the unlocked dialog reached the server (bots joined it)")
    finally:
        for p in (cp, sp):
            if p.poll() is None:
                p.kill()
                p.wait()
        clog.close(); slog.close()


def silent_scenario():
    """A server that stops answering after the join: the probe times out, nothing unlocks."""
    base = tempfile.mkdtemp(prefix="srvctl-", dir=os.environ.get("TMPDIR"))
    port = free_port()
    sp, slog = start_server(port, 0, os.path.join(base, "sv"))
    cp, clog, c = client(os.path.join(base, "cl"), f"defer 3 \"connect 127.0.0.1:{port}\"\n")
    try:
        joined = wait(c, lambda: in_game(c), 90)
        check(joined, "[no answer] the client joined the server")
        if not joined:
            return
        c.settle(quiet=1.0, limit=5)
        check(open_control(c) and asks_password(c), "[no answer] Server control asks for the password")
        os.kill(sp.pid, signal.SIGSTOP)  # the server stops answering (its process is frozen)
        t0 = time.monotonic()
        type_and_unlock(c, TESTPW)
        ok = wait(c, lambda: hint(c) == "Server did not answer", 15)
        dt = time.monotonic() - t0
        check(ok and not unlocked(c) and asks_password(c), f"[no answer] no answer shows \"Server did not answer\" and does not unlock ({hint(c)!r} after {dt:.1f} s)")
        check(verdicts(os.path.join(base, "cl"))[-1:] == ["timeout"], f"[no answer] the menu logged the timeout ({verdicts(os.path.join(base, 'cl'))})")
        check(dt < 8, f"[no answer] the wait is short (under 8 s) ({dt:.1f} s)")
        os.kill(sp.pid, signal.SIGCONT)
        time.sleep(2)  # the thawed server answers the old challenge request; it drops a new one within 0.5 s of that
        # and the dialog is usable again afterwards
        type_and_unlock(c, TESTPW)
        check(wait(c, lambda: unlocked(c), 15), "[no answer] once the server answers again the right password unlocks")
    finally:
        for p in (cp, sp):
            if p.poll() is None:
                try:
                    os.kill(p.pid, signal.SIGCONT)
                except OSError:
                    pass
                p.kill()
                p.wait()
        clog.close(); slog.close()


def local_scenario():
    """A game this device hosts: no password, no rcon."""
    base = tempfile.mkdtemp(prefix="srvctl-", dir=os.environ.get("TMPDIR"))
    cp, clog, c = client(os.path.join(base, "cl"), "")
    try:
        c.send({"t": "ev", "ev": "action", "node": "home/tiles/sp", "action": "sp"})
        c.settle()
        c.send({"t": "ev", "ev": "action", "node": "sp/modes/grid/dm", "action": "mode.dm"})
        c.settle()
        c.send({"t": "ev", "ev": "action", "node": "sp", "action": "play"})
        ok = wait(c, lambda: app(c).get("context") == "game" and "pause/match" in c.nodes, 90)
        c.settle(quiet=2, limit=6)
        check(ok, "[local] a local game is running (this engine hosts it)")
        check(open_control(c), "[local] Server control opens")
        check(unlocked(c) and not asks_password(c) and "ctl/forget" not in c.nodes,
              "[local] it shows the controls at once: no password form, no Forget")
        check("ctl/hint" not in c.nodes or "rcon" not in props(c, "ctl/hint").get("body", "").lower(), "[local] it does not say commands go over rcon")
        # the commands go straight to the local server
        c.send({"t": "ev", "ev": "value", "node": "ctl/bots/n", "value": 3})
        c.settle(quiet=0.3, limit=2)
        c.send({"t": "ev", "ev": "action", "node": "ctl/bots/apply", "action": "ctl.bots"})
        time.sleep(5)
        log = open(os.path.join(base, "cl", "engine.log"), errors="replace").read()
        check("rcon" not in log.lower().replace("rcon_", ""), "[local] no rcon command was sent")
    finally:
        if cp.poll() is None:
            cp.kill(); cp.wait()
        clog.close()


def main():
    for secure in (0, 1, 2):  # a stock client (rcon_secure 1) against every server mode
        remote_scenario(secure)
    remote_scenario(0, client_secure=0)  # plain rcon on both ends
    silent_scenario()
    local_scenario()
    print("FAILED: %d" % et.fails if et.fails else "server_control_test: all passed")
    return 1 if et.fails else 0


if __name__ == "__main__":
    sys.exit(main())
