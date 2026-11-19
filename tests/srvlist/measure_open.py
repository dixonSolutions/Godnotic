#!/usr/bin/env python3
"""Time to the first server rows when Multiplayer is opened, with the real engine, offscreen.

A saved list of 100 servers exists from an earlier session; the servers answer as usual (or, with
--hold, not at all, so the rows can only come from the cache and unanswered ones age out). A bridge client plays the
renderer: it waits for Home, then taps the Multiplayer card and asks for the "servers.all" window
(every 25 ms, as a list that has just been created and is told about a changed source would).

  measure_open.py --engine E --basedir B --work W [--runs 5] [--port 27990]

Prints, per run and as medians, in milliseconds:
  home_rows   from the bridge's hello until Home's Multiplayer card lists a server
  first_rows  from the tap on the card until the window holds a row
  whole list  from the tap until the window holds every saved row and keeps them (0 = never missing;
              "lowest count" is the fewest rows any window held in the 3 s after the tap)
Not measured: the renderer's own drawing time after the rows arrive.
"""
import argparse, json, os, shutil, socket, statistics, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("--engine", required=True)
ap.add_argument("--basedir", required=True)
ap.add_argument("--work", required=True)
ap.add_argument("--runs", type=int, default=5)
ap.add_argument("--port", type=int, default=27990)
ap.add_argument("--hold", type=float, default=0, help="seconds the fake servers hold every reply (cache only)")
ap.add_argument("--settle", type=float, default=2.0, help="seconds on Home before the tap")
a = ap.parse_args()
os.makedirs(a.work, exist_ok=True)
STATE = os.path.join(a.work, "state.json")
REPORT = os.path.join(a.work, "report.json")
SERVERS = [{"ip": "127.77.1.%d" % (i + 1), "port": 26000, "alive": True, "latency_ms": 15 + (i * 37) % 236,
            "humans": 3 if i % 10 == 0 else 0, "bots": 0, "max": 16, "map": "stormkeep", "name": "Fake %d" % i}
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


net = subprocess.Popen([sys.executable, os.path.join(HERE, "fakenet.py"), "--state", STATE, "--report", REPORT,
                        "--master-port", str(a.port)])
try:
    time.sleep(1.0)
    # prime: one session with answers, so the list is saved on quit
    write_state(0)
    prime = os.path.join(a.work, "prime")
    shutil.rmtree(prime, ignore_errors=True)
    os.makedirs(os.path.join(prime, "data"))
    with open(os.path.join(prime, "data", "prime.cfg"), "w") as f:
        f.write("defer 1 net_slist_open\ndefer 10 quit\n")
    start_engine(prime, ["+exec", "prime.cfg"], "prime").wait()
    saved = os.path.join(prime, "data", "touch_serverlist_info.txt")
    n_saved = len(open(saved).readlines()) - 1 if os.path.exists(saved) else 0
    print("primed: %d saved rows" % n_saved)
    if n_saved < 50:
        sys.exit("priming failed")
    write_state(a.hold)   # --hold: seconds every reply is held from here on

    home, first, full, dips = [], [], [], []
    for run in range(a.runs):
        ud = os.path.join(a.work, "run%d" % run)
        shutil.rmtree(ud, ignore_errors=True)
        shutil.copytree(prime, ud)
        sock = os.path.join(ud, "run", "uib.sock")
        t_spawn = time.monotonic()
        p = start_engine(ud, ["-uibridge", "listen:" + sock], "run%d" % run)
        try:
            c = Conn(sock)
            c.send({"t": "hello", "schema": "1.0", "renderer": {"name": "measure_open"}, "caps": {"widthClass": "expanded"}})
            t_hello = time.monotonic()
            end = t_hello + a.settle
            t_home = None
            while time.monotonic() < end:
                c.pump(0.02)
                if t_home is None and "Fake" in home_sub(c):
                    t_home = time.monotonic() - t_hello
            home.append(t_home)
            if os.environ.get('MEASURE_TRACE'):
                print('   home subtitle:', repr(home_sub(c)))
            c.send({"t": "ev", "ev": "action", "node": "home/tiles/mp", "action": "mp"})
            t0 = time.monotonic()
            samples = []
            next_ask = 0
            asked = set()
            while time.monotonic() - t0 < 3:
                now = time.monotonic()
                if now >= next_ask:
                    asked.add(c.send({"t": "window", "source": "servers.all", "offset": 0, "limit": 100}))
                    next_ask = now + 0.025
                c.pump(0.005)
                for mid, rep in list(c.replies.items()):
                    if mid in asked:
                        samples.append((time.monotonic() - t0, len(rep.get("rows", []))))
                    del c.replies[mid]
            if os.environ.get("MEASURE_TRACE"):
                last = None
                for t, g in samples:
                    if g != last:
                        print("   t=%4.0f ms rows=%d" % (1000 * t, g))
                        last = g
            first_t = next((t for t, g in samples if g > 0), None)
            lowest = min((g for t, g in samples), default=0)
            below = [t for t, g in samples if g < n_saved]
            # when the whole saved list was last missing: 0 if it never was
            whole = 0.0 if not below else (None if below[-1] == samples[-1][0] else below[-1])
            first.append(first_t)
            full.append(whole)
            dips.append(lowest)
            f = lambda v: "never" if v is None else "%.0f ms" % (1000 * v)
            print("run %d: home_rows %s, first_rows %s, lowest row count after the tap %d of %d, whole list present from %s"
                  % (run, f(home[-1]), f(first_t), lowest, n_saved, f(whole)))
        finally:
            p.terminate()
            p.wait()
    med = lambda l: "never" if any(v is None for v in l) or not l else "%.0f ms" % (1000 * statistics.median(l))
    print("median of %d: home_rows %s, first_rows %s, whole list present from %s, lowest count %d of %d"
          % (a.runs, med(home), med(first), med(full), statistics.median(dips), n_saved))
finally:
    net.terminate()
    net.wait()
