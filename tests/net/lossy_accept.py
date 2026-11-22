#!/usr/bin/env python3
"""A fake server for measuring how long a join's first four packets take on a
lossy path (scripts/test-net-connect.sh "loss" case).

    lossy_accept.py PORT LOSS SEED SECONDS

Answers getchallenge with a challenge and the first connect with accept, like
drop_connects.py, but every packet it receives is lost with probability LOSS
and so is every answer it would send (LOSS each way). One line per join:
"<seconds from the join's first request to the accept>". A join ends at the
accept; the next request after it starts a new one (the client disconnects
and connects again from its config).
"""
import random
import socket
import sys
import time

port, loss, seed, secs = int(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
rnd = random.Random(seed)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", port))
s.settimeout(0.1)
end = time.time() + secs
t0 = None
while time.time() < end:
    try:
        data, addr = s.recvfrom(4096)
    except OSError:
        continue
    if not data.startswith(b"\xff\xff\xff\xff"):
        continue
    now = time.time()
    body = data[4:]
    is_req = body.startswith(b"getchallenge") or body.startswith(b"connect")
    if is_req and t0 is None:
        t0 = now
    if not is_req or rnd.random() < loss:
        continue
    if body.startswith(b"getchallenge"):
        if rnd.random() >= loss:
            s.sendto(b"\xff\xff\xff\xffchallenge 12345abc", addr)
    elif t0 is not None:
        if rnd.random() >= loss:
            s.sendto(b"\xff\xff\xff\xffaccept", addr)
            print(f"{now - t0:.3f}", flush=True)
            t0 = None
