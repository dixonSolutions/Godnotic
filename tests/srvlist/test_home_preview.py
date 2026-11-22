#!/usr/bin/env python3
"""Home's Multiplayer card on a cold start (nothing saved) against fake servers that answer:
the card is the live server list: the servers with the MOST players first, not the first ones
that answered (an order kept from the first reply made the preview useless), six lines, no
picture; with no server at all it is empty (no placeholder, never a loading text).

  test_home_preview.py --engine E --basedir B --work W [--port 27991]
"""
import argparse, json, os, shutil, socket, statistics, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("--engine", required=True)
ap.add_argument("--basedir", required=True)
ap.add_argument("--work", required=True)
ap.add_argument("--port", type=int, default=27990)
ap.add_argument("--hold", type=float, default=0, help="seconds the fake servers hold every reply (cache only)")
ap.add_argument("--settle", type=float, default=2.0, help="seconds on Home before the tap")
a = ap.parse_args()
os.makedirs(a.work, exist_ok=True)
STATE = os.path.join(a.work, "state.json")
REPORT = os.path.join(a.work, "report.json")
SERVERS = [{"ip": "127.77.1.%d" % (i + 1), "port": 26000, "alive": True, "latency_ms": 15 + (i * 37) % 236,
            "humans": 3 if i % 10 == 0 else 0, "bots": 0, "max": 16,
            "map": "nopicmap_xyz" if i % 10 == 0 else "stormkeep", "name": "Fake %d" % (0 if i == 10 else i)}
           for i in range(100)]


def write_state(hold):
    with open(STATE + ".tmp", "w") as f:
        json.dump({"servers": SERVERS, "hold_answers": hold}, f)
    os.replace(STATE + ".tmp", STATE)
    time.sleep(0.4)


preload = os.path.join(a.work, "loopback_only.so")
subprocess.run(["cc", "-shared", "-fPIC", os.path.join(HERE, "loopback_only.c"), "-o", preload, "-ldl"], check=True)


def start_engine(userdir, extra, name):
    os.makedirs(os.path.join(userdir, "data"), exist_ok=True)
    with open(os.path.join(userdir, "data", "autoexec.cfg"), "w") as f:
        f.write('seta touch_profile_done 1\nseta _cl_name "mptime"\nseta cl_allow_uid2name 0\n'
                'sv_master1 "127.0.0.1:%d"\n' % a.port
                + "".join('%s ""\n' % m for m in ("sv_master2", "sv_master3", "sv_master4", "sv_masterextra1", "sv_masterextra2", "sv_masterextra3")))
    env = dict(os.environ, SDL_VIDEODRIVER="offscreen", SDL_AUDIODRIVER="dummy", LD_PRELOAD=preload)
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
    cmd = ["timeout", "-k", "5", "90", a.engine, "-xonotic", "-basedir", a.basedir, "-userdir", userdir, "-nosound",
           "+vid_fullscreen", "0", "+vid_width", "640", "+vid_height", "360", "+set", "_touch_assets_ready", "1",
           "+set", "net_slist_fast", "1", "+set", "net_slist_lan_repeats", "0", "+set", "net_slist_favorites", "",
           "+set", "net_slist_seed", "0"] + extra
    log = open(os.path.join(a.work, name + ".log"), "wb")
    return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env, cwd=a.basedir)


class Conn:
    def __init__(self, path):
        end = time.monotonic() + 60
        while True:
            try:
                self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                self.s.connect(path)
                break
            except OSError:
                if time.monotonic() > end:
                    raise
                time.sleep(0.05)
        self.buf = b""
        self.mid = 0
        self.nodes = {}
        self.replies = {}

    def send(self, m):
        self.mid += 1
        self.s.sendall((json.dumps(dict({"uib": 1, "id": self.mid}, **m)) + "\n").encode())
        return self.mid

    def pump(self, timeout):
        self.s.settimeout(timeout)
        try:
            c = self.s.recv(1 << 20)
        except socket.timeout:
            return
        if not c:
            raise EOFError
        self.buf += c
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            m = json.loads(line)
            if m.get("t") == "snapshot":
                self.nodes = {n["id"]: n for n in m["nodes"]}
            elif m.get("t") == "diff":
                for d in m.get("del", []):
                    self.nodes.pop(d, None)
                for n in m.get("set", []):
                    self.nodes[n["id"]] = n
            elif m.get("t") == "rows":
                self.replies[m["re"]] = m


def home_sub(c):
    return c.nodes.get("home/tiles/mp", {}).get("props", {}).get("subtitle", "")



fails = 0


def check(cond, msg):
    global fails
    print(("ok   " if cond else "FAIL ") + msg)
    if not cond:
        fails += 1


net = subprocess.Popen([sys.executable, os.path.join(HERE, "fakenet.py"), "--state", STATE, "--report", REPORT,
                        "--master-port", str(a.port)])
try:
    time.sleep(1.0)
    # no server exists yet: the empty state
    write_state(0)
    for s in SERVERS:
        s["alive"] = False
    with open(STATE + ".tmp", "w") as f:
        json.dump({"servers": SERVERS, "hold_answers": 0}, f)
    os.replace(STATE + ".tmp", STATE)
    time.sleep(0.4)
    ud = os.path.join(a.work, "cold")
    shutil.rmtree(ud, ignore_errors=True)
    sock = os.path.join(ud, "run", "uib.sock")
    p = start_engine(ud, ["-uibridge", "listen:" + sock], "cold")
    try:
        c = Conn(sock)
        c.send({"t": "hello", "schema": "1.0", "renderer": {"name": "test_home_preview"}, "caps": {"widthClass": "expanded"}})
        end = time.monotonic() + 3
        while time.monotonic() < end:
            c.pump(0.05)
        props = c.nodes.get("home/tiles/mp", {}).get("props", {})
        sub = props.get("subtitle", "")
        check(sub == "", "empty: the card lists nothing, no placeholder or loading text (%r)" % sub)
        check(not props.get("image"), "empty: the card has no picture, it is a server list (%r)" % props.get("image"))
        # servers come alive: the card lists the busiest ones first
        for s in SERVERS:
            s["alive"] = True
        with open(STATE + ".tmp", "w") as f:
            json.dump({"servers": SERVERS, "hold_answers": 0}, f)
        os.replace(STATE + ".tmp", STATE)
        # Home refreshes the list by itself every 30 s
        end = time.monotonic() + 45
        lines = []
        while time.monotonic() < end:
            c.pump(0.1)
            sub = c.nodes.get("home/tiles/mp", {}).get("props", {}).get("subtitle", "")
            lines = sub.split("\n")
            if len(lines) == 6 and all(l.rstrip().split(" ")[-1].startswith("3/") for l in lines):
                break
        check(len(lines) == 6 and all(l.rstrip().split(" ")[-1].startswith("3/") for l in lines),
              "live: six of the busiest servers (3 players each) fill the card (%r)" % lines)
        check(len(set(lines)) == len(lines),
              "live: a server listed under two addresses (servers 0 and 10 here) is one line on the card (%r)" % lines)
        image = c.nodes.get("home/tiles/mp", {}).get("props", {}).get("image", "")
        check(not image, "live: no picture on the server list card (%r)" % image)
    finally:
        p.terminate()
        p.wait()
finally:
    net.terminate()
    net.wait()
sys.exit(1 if fails else 0)
