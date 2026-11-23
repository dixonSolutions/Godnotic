#!/usr/bin/env python3
"""Time a headless Godnotic client joining a real (or local) server: ms after Join to the first
map picture (and whether it is the right map), to the first download byte, to the world, to the
game. Used for docs/NETWORK.md's "Joining a public server" table.

  BASEDIR=<basedir> python3 -I scripts/bench-srvjoin.py [-n RUNS] [-m MAP] [--warm] [-o OUTDIR] ADDRESS...

ADDRESS is host:port. -m names the map the server list shows for it (a join's picture must be
that map's). --warm keeps the userdir (downloaded packs) between the runs of one address; without
it every run starts with an empty userdir (a first join). The client runs offscreen with the
front-end bridge echoing (`fe_echo 1`): every `FE> topic {json}` line is stamped with the time it
was read, and the milestones are taken from those stamps (t0 = the moment the connect command ran).
Nothing is sent to a server except an ordinary join; the client leaves after the game is entered.
"""
import argparse, json, os, shutil, signal, subprocess, sys, tempfile, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run_once(addr, mapname, userdir, outdir, tag, engine, basedir, cpus, limit, extra, cap_mb=70.0):
    os.makedirs(os.path.join(userdir, "data"), exist_ok=True)
    with open(os.path.join(userdir, "data", "t.cfg"), "w") as f:
        f.write("""set touch_profile_done 1
set touch_always_autojoin 0
set _cl_name SrvJoinBench
set vid_touchscreen 1
set fe_echo 1
set touch_loadtime 1
fe attach 1
alias xt_p "exec p.cfg; defer 0.05 xt_p"
defer 0.05 xt_p
""")
    open(os.path.join(userdir, "data", "p.cfg"), "w").close()
    env = dict(os.environ, SDL_VIDEODRIVER="offscreen", SDL_AUDIODRIVER="dummy")
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
    cmd = ["taskset", "-c", cpus, engine, "-xonotic", "-basedir", basedir, "-userdir", userdir,
           "+vid_fullscreen", "0", "+vid_width", "640", "+vid_height", "360", "+set", "_touch_assets_ready", "1",
           "+exec", "t.cfg"] + extra
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, bufsize=0)
    lines = []  # (t, text)
    lock = threading.Lock()

    def reader():
        buf = b""
        while True:
            c = p.stdout.read(4096)
            if not c:
                break
            now = time.monotonic()
            buf += c
            while b"\n" in buf:
                ln, _, buf = buf.partition(b"\n")
                with lock:
                    lines.append((now, ln.decode("utf-8", "replace")))
    th = threading.Thread(target=reader, daemon=True)
    th.start()

    def snap():
        with lock:
            return list(lines)
    # wait for the menu (first app snapshot), then press Join
    t_start = time.monotonic()
    while time.monotonic() - t_start < 60 and not any(l.startswith("FE> app") for _, l in snap()):
        time.sleep(0.05)
    time.sleep(1.0)
    with open(os.path.join(userdir, "data", "p.cfg"), "w") as f:
        f.write('alias xt_p ""; echo MARK-JOIN; connect %s\n' % addr)
    ingame_at = None
    deadline = time.monotonic() + limit
    shot_taken = False
    capped = False
    while time.monotonic() < deadline and p.poll() is None:
        L = snap()
        for t, l in L[-40:]:
            if l.startswith("FE> connect ") and '"dl_all_total":' in l:
                try:
                    if float(l.split('"dl_all_total":')[1].split(",")[0]) > cap_mb * 1048576:
                        capped = True
                except ValueError:
                    pass
        if capped:
            break
        for t, l in L:
            if l.startswith("FE> app ") and '"state":"ingame"' in l and any(x.startswith("MARK-JOIN") for _, x in L):
                ingame_at = ingame_at or t
        if ingame_at and time.monotonic() - ingame_at > 2.5:
            break
        time.sleep(0.1)
    # one engine screenshot of the world, then leave
    try:
        with open(os.path.join(userdir, "data", "p.cfg"), "w") as f:
            f.write('alias xt_p ""; screenshot srvjoin_%s; defer 1 quit\n' % tag)
        alias_wait = time.monotonic() + 3
        while time.monotonic() < alias_wait and p.poll() is None:
            time.sleep(0.1)
    except OSError:
        pass
    if p.poll() is None:
        p.send_signal(signal.SIGTERM)
        try:
            p.wait(5)
        except subprocess.TimeoutExpired:
            p.kill()
    th.join(2)
    L = snap()
    with open(os.path.join(outdir, tag + ".log"), "w") as f:
        t0 = L[0][0] if L else 0
        for t, l in L:
            f.write("%8.3f %s\n" % (t - t0, l))
    r = analyse(L, addr, mapname, userdir, outdir, tag)
    pf = r.get("picture_file", "")
    if pf and os.path.isfile(pf):  # the picture the card showed first (the evidence for "right map")
        dst = os.path.join(outdir, tag + "-picture" + os.path.splitext(pf)[1])
        shutil.copy(pf, dst)
        r["picture_copy"] = os.path.relpath(dst, ROOT)
    chk = subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tests", "touch-joinseq", "preview_steady.py"), os.path.join(outdir, tag + ".log")],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    r["preview_steady"] = "ok" if chk.returncode == 0 else chk.stdout.strip()[:300]  # no blank, no revert (tests/touch-joinseq/preview_steady.py)
    if capped:
        r["aborted"] = "downloads over %d MB" % cap_mb
    return r


def analyse(L, addr, mapname, userdir, outdir, tag):
    t0 = next((t for t, l in L if l.startswith("MARK-JOIN")), None)
    res = {"addr": addr, "tag": tag}
    if t0 is None:
        res["error"] = "never pressed join"
        return res
    ms = lambda t: int(round((t - t0) * 1000))
    stages = []
    pic = {}
    firstdl = None
    last = {}
    sig_first = None
    for t, l in L:
        if t < t0 or not l.startswith("FE> "):
            continue
        topic, _, body = l[4:].partition(" ")
        try:
            d = json.loads(body)
        except ValueError:
            continue
        if topic == "connect":
            st = d.get("stage", "")
            if not stages or stages[-1][1] != st:
                stages.append((ms(t), st))
            if d.get("join_map") and "join_map" not in res:
                res["join_map_ms"] = ms(t)
                res["join_map"] = d["join_map"]
            if d.get("levelshot") and "picture_ms" not in res:
                res["picture_ms"] = ms(t)
                res["picture_kind"] = d.get("levelshot_kind")
                res["picture_file"] = d.get("levelshot")
                res["picture_map"] = d.get("join_map", "")
                res["picture_right_map"] = (not mapname) or (mapname in os.path.basename(d["levelshot"]) and d.get("join_map") == mapname)
            if d.get("levelshot_kind") == "map" and "map_picture_ms" not in res:
                res["map_picture_ms"] = ms(t)
            if d.get("sig_bytes", 0) > 0 and sig_first is None:
                sig_first = ms(t)
            res["downloaded_bytes"] = max(res.get("downloaded_bytes", 0), d.get("dl_all_bytes", 0))
            if d.get("dl_all_bytes", 0) > 0 and firstdl is None:
                firstdl = ms(t)
            if d.get("wait_s", -1) >= 0 and "connected_ms" not in res:
                res["connected_ms"] = ms(t)  # accepted: the server's first data starts to arrive
            if d.get("signon", 0) >= 1 and "first_data_done_ms" not in res:
                res["first_data_done_ms"] = ms(t)  # serverinfo and the precache lists are in
            if d.get("dl_file") == "csprogs.dat":
                res.setdefault("csqc_download_ms", [ms(t), ms(t)])[1] = ms(t)
            if d.get("world") and "world_ms" not in res:
                res["world_ms"] = ms(t)
            last = d
        if topic == "app" and d.get("state") == "ingame" and "ingame_ms" not in res:
            res["ingame_ms"] = ms(t)
            res["ingame_map"] = d.get("map")
    res["stages"] = stages
    if firstdl is not None:
        res["first_download_byte_ms"] = firstdl
    res["downloaded_bytes"] = max(res.get("downloaded_bytes", 0), last.get("dl_all_bytes", 0))
    for t, l in L:  # engine load table at the first game frame
        if "LOADTIME" in l:
            res.setdefault("loadtime", []).append(l.strip()[:200])
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=1)
    ap.add_argument("-m", default="")
    ap.add_argument("--warm", action="store_true")
    ap.add_argument("-o", default=os.path.join(ROOT, "build", "srvjoin"))
    ap.add_argument("-l", type=int, default=90, help="seconds to wait for the game per run")
    ap.add_argument("--cpus", default=os.environ.get("SRVJOIN_CPUS", "8-15"))
    ap.add_argument("--engine", default=os.environ.get("ENGINE", os.path.join(ROOT, "build", "bin", "xonotic")))
    ap.add_argument("--cap", type=float, default=70.0, help="abort a run whose downloads exceed this many MB")
    ap.add_argument("--label", default="run")
    ap.add_argument("--extra", default="", help="extra engine args (a quoted string)")
    ap.add_argument("addr", nargs="+")
    a = ap.parse_args()
    basedir = os.environ["BASEDIR"]
    os.makedirs(a.o, exist_ok=True)
    out = []
    for addr in a.addr:
        keep = None
        for i in range(a.n):
            tmp = keep or tempfile.mkdtemp(prefix="srvjoin.", dir=os.environ.get("TMPDIR"))
            if a.warm:
                keep = tmp
            tag = "%s-%s-%d" % (a.label, addr.replace(":", "_"), i)
            try:
                r = run_once(addr, a.m, tmp, a.o, tag, a.engine, basedir, a.cpus, a.l, a.extra.split(), a.cap)
            finally:
                if not a.warm:
                    shots = os.path.join(tmp, "data", "screenshots")
                    if os.path.isdir(shots):
                        for f in os.listdir(shots):
                            shutil.copy(os.path.join(shots, f), os.path.join(a.o, tag + "-" + f))
                    shutil.rmtree(tmp, ignore_errors=True)
            print(json.dumps(r), flush=True)
            out.append(r)
        if keep:
            shots = os.path.join(keep, "data", "screenshots")
            if os.path.isdir(shots):
                for f in os.listdir(shots):
                    shutil.copy(os.path.join(shots, f), os.path.join(a.o, tag + "-" + f))
            shutil.rmtree(keep, ignore_errors=True)
    with open(os.path.join(a.o, a.label + ".json"), "w") as f:
        json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
