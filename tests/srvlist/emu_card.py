#!/usr/bin/env python3
"""Cold-start measurements of Home's Multiplayer card on an Android emulator (adb), from the app's
own log lines (device clock, `logcat -v epoch`):

  screen home        "Native UI: screen home"                       (the Home screen is on)
  card rows          "Native UI: home card rows=N ..." with N > 0   (the card lists servers)
  card picture       "Native UI: home card picture shown pk3:maps/..." (the top server's map: not xoylent)
  tap -> Multiplayer a tap on the card N s after the Home screen appeared, then "Native UI: screen mp"

  emu_card.py --pkg P --runs 5 --delays 0.3,1,2 [--serial emulator-5554] [--tap X,Y] [--wait 12]

--tap defaults to the card's centre found in a uiautomator dump (content-desc starting "Multiplayer").
Prints one line per run and a summary; exits 1 when a tap did not open Multiplayer.
"""
import argparse, re, statistics, subprocess, sys, threading, time, xml.etree.ElementTree as ET

ap = argparse.ArgumentParser()
ap.add_argument("--adb", default="adb")
ap.add_argument("--serial", default="emulator-5554")
ap.add_argument("--pkg", default="io.github.dixonsolutions.godnotic.mplist")
ap.add_argument("--activity", default="io.github.dixonsolutions.godnotic.GodnoticBootActivity")
ap.add_argument("--runs", type=int, default=5)
ap.add_argument("--delays", default="0.3,1,2")
ap.add_argument("--tap", default="")
ap.add_argument("--wait", type=float, default=12, help="seconds to wait for Multiplayer after the tap")
ap.add_argument("--no-tap", action="store_true", help="only measure rows and picture (no tap)")
a = ap.parse_args()


def adb(*args, check=False, timeout=60):
    r = subprocess.run([a.adb, "-s", a.serial] + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout


def card_center():
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml = adb("shell", "cat", "/sdcard/uidump.xml")
    for n in ET.fromstring(xml[xml.index("<"):]).iter("node"):
        if n.get("content-desc", "").startswith("Multiplayer"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds"))
            x0, y0, x1, y1 = map(int, m.groups())
            return (x0 + x1) // 2, (y0 + y1) // 2
    raise SystemExit("no Multiplayer card in the uiautomator dump")


class Log:
    def __init__(self):
        self.lines = []  # (device epoch, text)
        self.lock = threading.Lock()
        self.p = subprocess.Popen([a.adb, "-s", a.serial, "logcat", "-v", "epoch", "Godnotic:V", "*:S"],
                                  stdout=subprocess.PIPE, text=True, bufsize=1)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        for line in self.p.stdout:
            m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+Godnotic\s*:\s*(.*)", line)
            if m:
                with self.lock:
                    self.lines.append((float(m.group(1)), m.group(2).rstrip()))

    def first(self, pat, after=0.0, since=0):
        rx = re.compile(pat)
        with self.lock:
            for t, s in self.lines[since:]:
                if t >= after and rx.search(s):
                    return t, s
        return None

    def clear(self):
        with self.lock:
            self.lines = []

    def stop(self):
        self.p.kill()


def run(log, delay, tapxy):
    adb("shell", "am", "force-stop", a.pkg)
    time.sleep(1.0)
    adb("logcat", "-c")
    log.clear()
    adb("shell", "am", "start", "-n", a.pkg + "/" + a.activity)
    end = time.time() + 90
    home = None
    while time.time() < end and not home:
        home = log.first(r"Native UI: screen home")
        time.sleep(0.05)
    if not home:
        return {"error": "no Home screen in 90 s"}
    t0 = home[0]
    seen_host = time.time()
    res = {"delay": delay}
    if not a.no_tap:
        # tap `delay` s after the Home screen showed (adb's own latency included: the touch log line says when it landed)
        wait = delay - (time.time() - seen_host)
        if wait > 0:
            time.sleep(wait)
        adb("shell", "input", "tap", str(tapxy[0]), str(tapxy[1]))
        down = log.first(r"Native UI: touch down", after=t0 - 1)
        res["touch_at"] = round(down[0] - t0, 3) if down else None
        t_end = time.time() + a.wait
        mp = None
        while time.time() < t_end and not mp:
            mp = log.first(r"Native UI: screen mp", after=t0)
            time.sleep(0.05)
        res["opened"] = bool(mp)
        res["tap_to_mp"] = round(mp[0] - down[0], 3) if mp and down else None
        res["action_logged"] = bool(log.first(r"Native UI: action ", after=t0))
    else:
        time.sleep(a.wait)
    rows = log.first(r"home card rows=[1-9]", after=t0 - 30)
    res["rows_at"] = round(rows[0] - t0, 3) if rows else None
    pic = log.first(r"home card picture shown", after=t0 - 30)
    res["pic_at"] = round(pic[0] - t0, 3) if pic else None
    top = log.first(r"home card picture shown pk3:maps/(?!xoylent)", after=t0 - 30)  # a server's map, not the stand-in
    res["top_map_pic_at"] = round(top[0] - t0, 3) if top else None
    first = log.first(r"home card rows=", after=t0 - 30)
    res["first_card"] = first[1] if first else None
    return res


def main():
    delays = [float(x) for x in a.delays.split(",")]
    log = Log()
    time.sleep(0.5)
    tapxy = tuple(int(v) for v in a.tap.split(",")) if a.tap else None
    if tapxy is None and not a.no_tap:
        adb("shell", "am", "start", "-n", a.pkg + "/" + a.activity)
        time.sleep(15)
        tapxy = card_center()
        print("card centre", tapxy)
    fails = 0
    allres = []
    for d in delays:
        for i in range(a.runs):
            r = run(log, d, tapxy)
            allres.append(r)
            print(r, flush=True)
            if not a.no_tap and not r.get("opened"):
                fails += 1
    log.stop()
    for d in delays:
        rs = [r for r in allres if r.get("delay") == d]
        ok = sum(1 for r in rs if r.get("opened"))
        rows = [r["rows_at"] for r in rs if r.get("rows_at") is not None]
        pics = [r["pic_at"] for r in rs if r.get("pic_at") is not None]
        print("delay %.1f s: opened %d/%d, rows at median %s s (%d of %d runs), picture at median %s s (%d of %d)" % (
            d, ok, len(rs), statistics.median(rows) if rows else "-", len(rows), len(rs),
            statistics.median(pics) if pics else "-", len(pics), len(rs)))
        if a.no_tap:
            break
    sys.exit(1 if fails else 0)


main()
