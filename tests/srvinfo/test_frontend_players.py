#!/usr/bin/env python3
"""Exercise the native servers topic against a getstatus responder."""
import json
import os
import re
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
                    b'\n12 40 2 "Alpha"\n-3 60 1 "Beta"\n20 30 2 "Gamma"\n'
                    b'5 80 "Charlie"\nnot a player\n', addr)


# A busy server: 45 players with long colour-coded names, a status of about 2.5 KB over many lines.
sock2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock2.bind(("127.0.0.1", 0))
sock2.settimeout(0.2)
port2 = sock2.getsockname()[1]
BUSY = "".join('%d %d %d "^x%03x[BOT]Player_%02d_with_a_long_^7coloured_name^1_and_more_text_yes"\n' % (50 - i, 20 + i, 1 + i % 2, 0x0f0 + i, i)
               for i in range(45)).encode()


def respond2():
    while not stop.is_set():
        try:
            data, addr = sock2.recvfrom(4096)
        except socket.timeout:
            continue
        if not data.startswith(b"\xff\xff\xff\xffgetstatus "):
            continue
        challenge = data.split(b" ", 1)[1].strip()
        info = (b"\\gamename\\Xonotic\\modname\\data\\gameversion\\806"
                b"\\sv_maxclients\\64\\clients\\45\\bots\\45\\mapname\\stormkeep"
                b"\\hostname\\Busy test\\protocol\\3\\qcstatus\\tdm\\challenge\\" + challenge)
        sock2.sendto(b'\xff\xff\xff\xffstatusResponse\n' + info + b'\n' + BUSY, addr)


thread = threading.Thread(target=respond, daemon=True)
thread.start()
thread2 = threading.Thread(target=respond2, daemon=True)
thread2.start()
try:
    with tempfile.TemporaryDirectory(prefix="srvinfo-") as temp:
        os.mkdir(os.path.join(temp, "data"))
        with open(os.path.join(temp, "data", "autoexec.cfg"), "w") as cfg:
            cfg.write('seta touch_profile_done 1\nseta _cl_name "test"\n')
            for key in ("sv_master1", "sv_master2", "sv_master3", "sv_master4",
                        "sv_masterextra1", "sv_masterextra2", "sv_masterextra3"):
                cfg.write(key + ' ""\n')
        with open(os.path.join(temp, "data", "test.cfg"), "w") as cfg:
            cfg.write('fe favorite 127.0.0.1:%d 1\nfe favorite 127.0.0.1:%d 1\nfe refresh internet\n'
                      'defer 6 "fe_dump servers; quit"\n' % (port, port2))
        env = dict(os.environ, SDL_VIDEODRIVER="offscreen", SDL_AUDIODRIVER="dummy")
        env.pop("DISPLAY", None)
        env.pop("WAYLAND_DISPLAY", None)
        run = subprocess.run([engine, "-xonotic", "-nosound", "-basedir", basedir,
                              "-userdir", temp, "+vid_fullscreen", "0", "+vid_width", "640",
                              "+vid_height", "360", "+set", "_touch_assets_ready", "1",
                              "+exec", "test.cfg"], env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, timeout=45, check=True)
        lines = run.stdout.decode("utf-8", "replace").splitlines()
        # the console prints ^x colour codes as ANSI colour escapes; they are not part of the JSON
        ansi = re.compile(r"\x1b\[[0-9;]*m")
        snapshots = [json.loads(ansi.sub("", line[len("FE servers "):])) for line in lines
                     if line.startswith("FE servers ")]
        rows = [row for snapshot in snapshots for row in snapshot["rows"]
                if row["a"] == "127.0.0.1:%d" % port]
        assert rows, "fake server missing from FE servers output"
        assert rows[-1]["player_status"] == ('12 40 2 "Alpha"\n-3 60 1 "Beta"\n'
                                             '20 30 2 "Gamma"\n5 80 "Charlie"\nnot a player\n'), rows[-1]
        assert rows[-1]["player_details"] == ('Red  -3  Beta\nBlue  20  Gamma\n'
                                              'Blue  12  Alpha\n5  Charlie\n'), rows[-1]
        print("ok   server details sort team scores, retain partial score rows and skip malformed lines")
        busy = [row for snapshot in snapshots for row in snapshot["rows"] if row["a"] == "127.0.0.1:%d" % port2]
        assert busy, "the busy fake server is missing from FE servers output"
        details = busy[-1]["player_details"].splitlines()
        assert len(details) >= 20, ("a 45-player status lists its players", len(details), busy[-1]["player_details"][:200])
        assert not any("unterminated or truncated" in l for l in lines), "a long status must not warn about a truncated string"
        print("ok   a 45-player status with long colour-coded names: %d rows listed, no truncation warning, no crash" % len(details))
finally:
    stop.set()
    thread.join()
    thread2.join()
    sock.close()
    sock2.close()
