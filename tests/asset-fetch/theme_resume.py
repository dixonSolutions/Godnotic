#!/usr/bin/env python3
"""Interrupted theme fetch resumes, and servers without ranges restart cleanly."""
import http.server
import os
import pathlib
import socket
import subprocess
import tempfile
import threading

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = bytes(range(256)) * 4096

class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        server = self.server
        requested = self.headers.get('Range')
        server.requests.append(requested)
        offset = int(requested.split('=')[1].split('-')[0]) if requested and server.ranges else 0
        self.send_response(206 if requested and server.ranges else 200)
        self.send_header('Content-Length', str(len(DATA) - offset))
        if requested and server.ranges:
            self.send_header('Content-Range', f'bytes {offset}-{len(DATA)-1}/{len(DATA)}')
        self.end_headers()
        first_interruption = server.interrupt and len(server.requests) == 1
        body = DATA[offset:len(DATA)//2 if first_interruption else len(DATA)]
        try:
            self.wfile.write(body)
            self.wfile.flush()
            if first_interruption:
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
        except (BrokenPipeError, ConnectionResetError):
            pass

def run(ranges, interrupted):
    server = Server(('127.0.0.1', 0), Handler)
    server.requests, server.ranges, server.interrupt = [], ranges, interrupted
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as tmp:
            dest = pathlib.Path(tmp) / 'godnotic-theme.pk3dir/sound/cdtracks/test.ogg'
            if not interrupted:
                dest.parent.mkdir(parents=True)
                pathlib.Path(str(dest) + '.partial').write_bytes(DATA[:len(DATA)//2])
            env = dict(os.environ, XONOTIC_MENU_THEME_TRACK='test',
                       XONOTIC_MENU_THEME_URL=f'http://127.0.0.1:{server.server_port}/theme')
            commands = 'source "$1"; '
            if interrupted:
                commands += 'xonotic_ensure_menu_theme_track "$2" && exit 9; '
            commands += 'xonotic_ensure_menu_theme_track "$2"'
            result = subprocess.run(['bash', '-c', commands, 'test', str(ROOT / 'scripts/lib/asset-fetch.sh'), tmp],
                                    env=env, capture_output=True, text=True, timeout=15)
            assert result.returncode == 0, result.stderr
            assert dest.read_bytes() == DATA
            assert not pathlib.Path(str(dest) + '.partial').exists()
            assert server.requests == ([None, 'bytes=524288-'] if interrupted else ['bytes=524288-', None]), server.requests
    finally:
        server.shutdown()
        server.server_close()

run(ranges=True, interrupted=True)
run(ranges=False, interrupted=False)
print('theme interruption resumes; no-range server restarts: PASS')
