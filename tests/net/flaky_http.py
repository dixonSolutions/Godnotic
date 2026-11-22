#!/usr/bin/env python3
"""An HTTP file server with Range support that fails on purpose, for the
"download retry" test (scripts/test-net-download.sh).

    flaky_http.py DIR PORT FAILS [BYTES]

The first FAILS requests for a file are cut after BYTES bytes (default
1 MiB) of their body: the connection closes with the Content-Length unmet,
as a Wi-Fi drop does. Later requests are served in full. A request
for "/err/..." answers 503 FAILS times first. Prints one line per request.
"""
import os, re, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.abspath(sys.argv[1]); PORT = int(sys.argv[2]); FAILS = int(sys.argv[3])
CUT = int(sys.argv[4]) if len(sys.argv) > 4 else 1 << 20
count = {}

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, f, *a): pass
    def _serve(self, head):
        name = self.path.split("?")[0].lstrip("/")
        err = name.startswith("err/")
        if err: name = name[4:]
        path = os.path.abspath(os.path.join(ROOT, name))
        if not path.startswith(ROOT + os.sep) or not os.path.isfile(path):
            self.send_error(404); return
        size = os.path.getsize(path); start, end = 0, size - 1
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m and (m.group(1) or m.group(2)):
            if m.group(1): start = int(m.group(1))
            if m.group(2): end = min(int(m.group(2)), size - 1)
            if start >= size:
                self.send_response(416); self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0"); self.end_headers(); return
        n = count.get(name, 0)
        if not head: count[name] = n + 1
        fail = (not head) and n < FAILS
        sys.stderr.write("%s %s %s range=%s fail=%s\n" % (time.strftime("%H:%M:%S"), "HEAD" if head else "GET", name, self.headers.get("Range"), fail)); sys.stderr.flush()
        if fail and err:
            self.send_error(503); return
        part = (start, end) != (0, size - 1)
        self.send_response(206 if part else 200)
        if part: self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(end - start + 1)); self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        if head: return
        left = end - start + 1; sent = 0
        with open(path, "rb") as f:
            f.seek(start)
            while left > 0:
                if fail and sent >= CUT:
                    self.close_connection = True; self.wfile.flush(); return
                chunk = f.read(min(65536, left)); self.wfile.write(chunk); left -= len(chunk); sent += len(chunk)
                time.sleep(len(chunk) / 3e6)
    def do_GET(self): self._serve(False)
    def do_HEAD(self): self._serve(True)

ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
