#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A stand-in for the engine as the app's UI backend, for scripts/test-linux-session.sh.

The app starts GODNOTIC_START with the UI bridge on fd 3 (`-uibridge fd:3`). This answers
hello with Home, takes a join as the engine does (a `submit` on mp/join/address) by switching to
the pause page of a running match (context "game"); the join field exists only on the
Multiplayer screen, which an `action` on home/tiles/mp opens, as in the engine, and then ends the way STUB_END says:

  quit    exit 0 after STUB_LIVE seconds (the player quit the game)
  crash   exit 1 after STUB_LIVE seconds
  stay    keep running

STUB_OUT is a directory: `joined` gets the address the app submitted, `starts` one line per
start of this stub (a second engine would add one). STUB_FIXTURES is tests/uibridge/fixtures.
"""
import json, os, socket, sys, time

out = os.environ["STUB_OUT"]
fx = os.environ["STUB_FIXTURES"]
end = os.environ.get("STUB_END", "quit")
live = float(os.environ.get("STUB_LIVE", "6"))
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "starts"), "a") as f:
    f.write("%d\n" % os.getpid())
sock = socket.socket(fileno=3)
rev = [0]


def snap(name):
    m = json.load(open(os.path.join(fx, name + ".json")))["snapshot"]
    rev[0] += 1
    m["rev"] = rev[0]
    return m


def send(m):
    sock.sendall((json.dumps(m) + "\n").encode())


buf = b""
on_mp = False
quit_at = None
sock.settimeout(0.2)
while True:
    if quit_at is not None and time.time() >= quit_at:
        sys.exit(0 if end == "quit" else 1)
    try:
        data = sock.recv(65536)
    except socket.timeout:
        continue
    if not data:
        break
    buf += data
    while b"\n" in buf:
        line, buf = buf.split(b"\n", 1)
        if not line.strip():
            continue
        m = json.loads(line)
        t = m.get("t")
        if t == "hello":
            send({"uib": 1, "t": "hello", "id": 1, "re": m.get("id", 0), "schema": "1.0", "engine": "stub", "session": "stub",
                  "limits": {"window": 200}, "features": ["rows"]})
            send(snap("home"))
        elif t == "ev" and m.get("ev") == "action" and m.get("node") == "home/tiles/mp" and m.get("action") == "mp":
            on_mp = True
            send(snap("servers"))
        elif t == "ev" and m.get("ev") == "submit" and m.get("node") == "mp/join/address" and not on_mp:
            # as the engine: only a screen that exists takes events
            send({"uib": 1, "t": "error", "id": 99, "re": m.get("id", 0), "code": "not_found", "msg": "no such node"})
        elif t == "ev" and m.get("ev") == "submit" and m.get("node") == "mp/join/address":
            with open(os.path.join(out, "joined"), "a") as f:
                f.write(str(m.get("value")) + "\n")
            send(snap("pause"))
            if end != "stay":
                quit_at = time.time() + live
        elif t == "sync":
            send(snap("pause" if quit_at is not None else "home"))
