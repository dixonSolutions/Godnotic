#!/usr/bin/env python3
"""An HTTP file server that misbehaves on purpose, for scripts/test-touch-packfail.sh.

    fault_http.py DIR PORT MODE

MODE is one of
    ok          serve files normally (with Range)
    drop:N      announce the whole file, send N bytes of it, close the connection
                (the server drops mid-file; every request, so retries fail too)
    stall:N     announce the file, send N bytes, then send nothing and keep the socket open
    notfound    answer 404 to everything
    error:CODE  answer HTTP status CODE (503) to everything
    garbage     serve every .pk3 request as 'not a zip' bytes of the announced length
"""
import os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT, PORT, MODE = os.path.abspath(sys.argv[1]), int(sys.argv[2]), sys.argv[3]

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s [%s]\n" % (time.strftime("%H:%M:%S"), fmt % a, MODE))
    def _path(self):
        p = os.path.abspath(os.path.join(ROOT, self.path.split("?")[0].lstrip("/")))
        return p if p.startswith(ROOT + os.sep) and os.path.isfile(p) else None
    def do_HEAD(self):
        self.do_GET(head=True)
    def do_GET(self, head=False):
        if MODE == "notfound":
            return self.send_error(404)
        if MODE.startswith("error:"):
            return self.send_error(int(MODE.split(":")[1]))
        p = self._path()
        if not p:
            return self.send_error(404)
        size = os.path.getsize(p)
        rng = self.headers.get("Range")
        start = 0
        if MODE == "ok" and rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a or 0)
            end = int(b) if b else size - 1
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            n = end - start + 1
        else:
            self.send_response(200)
            n = size
        self.send_header("Content-Length", str(n))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        if head:
            return
        with open(p, "rb") as f:
            f.seek(start)
            data = f.read(n)
        if MODE == "garbage":
            data = (b"this is not a zip file " * (n // 23 + 1))[:n]
        limit = n
        if MODE.startswith(("drop:", "stall:")):
            limit = min(n, int(MODE.split(":")[1]))
        try:
            for i in range(0, limit, 65536):
                self.wfile.write(data[i:min(limit, i + 65536)])
            self.wfile.flush()
            if MODE.startswith("stall:"):
                time.sleep(3600)
        except (BrokenPipeError, ConnectionResetError):
            pass
        if MODE.startswith("drop:"):
            self.close_connection = True
            self.connection.shutdown(2)

ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
