#!/usr/bin/env python3
"""Exercise the native servers topic against a getstatus responder."""
import json
import os
import socket
import subprocess
import tempfile
import threading

engine = os.environ["ENGINE"]
basedir = os.environ["BASEDIR"]
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", 0))
sock.settimeout(0.2)
port = sock.getsockname()[1]
stop = threading.Event()


def respond():
    while not stop.is_set():
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        if not data.startswith(b"\xff\xff\xff\xffgetstatus "):
            continue
        challenge = data.split(b" ", 1)[1].strip()
        info = (b"\\gamename\\Xonotic\\modname\\data\\gameversion\\806"
                b"\\sv_maxclients\\16\\clients\\2\\bots\\0\\mapname\\stormkeep"
                b"\\hostname\\Player test\\protocol\\3\\qcstatus\\dm\\challenge\\" + challenge)
        sock.sendto(b'\xff\xff\xff\xffstatusResponse\n' + info +
                    b'\n12 40 "Alpha"\n-3 60 "Beta"\n', addr)


thread = threading.Thread(target=respond, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory(prefix="srvinfo-") as temp:
        os.mkdir(os.path.join(temp, "data"))
        with open(os.path.join(temp, "data", "autoexec.cfg"), "w") as cfg:
            cfg.write('seta touch_profile_done 1\nseta _cl_name "test"\n')
            for key in ("sv_master1", "sv_master2", "sv_master3", "sv_master4",
                        "sv_masterextra1", "sv_masterextra2", "sv_masterextra3"):
                cfg.write(key + ' ""\n')
        with open(os.path.join(temp, "data", "test.cfg"), "w") as cfg:
            cfg.write('fe favorite 127.0.0.1:%d 1\nfe refresh internet\n'
                      'defer 6 "fe_dump servers; quit"\n' % port)
        env = dict(os.environ, SDL_VIDEODRIVER="offscreen", SDL_AUDIODRIVER="dummy")
        env.pop("DISPLAY", None)
        env.pop("WAYLAND_DISPLAY", None)
        run = subprocess.run([engine, "-xonotic", "-nosound", "-basedir", basedir,
                              "-userdir", temp, "+vid_fullscreen", "0", "+vid_width", "640",
                              "+vid_height", "360", "+set", "_touch_assets_ready", "1",
                              "+exec", "test.cfg"], env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, timeout=45, check=True)
        lines = run.stdout.decode("utf-8", "replace").splitlines()
        snapshots = [json.loads(line[len("FE servers "):]) for line in lines
                     if line.startswith("FE servers ")]
        rows = [row for snapshot in snapshots for row in snapshot["rows"]
                if row["a"] == "127.0.0.1:%d" % port]
        assert rows, "fake server missing from FE servers output"
        assert rows[-1]["player_status"] == '12 40 "Alpha"\n-3 60 "Beta"\n', rows[-1]
        print("ok   native server row preserves scores, ping, names and newlines from getstatus")
finally:
    stop.set()
    thread.join()
    sock.close()
