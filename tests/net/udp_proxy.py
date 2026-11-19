#!/usr/bin/env python3
"""Bidirectional UDP relay for network tests without tc.

The control file holds one line: loss fraction, one-way delay seconds, freeze
seconds and, optionally, jitter seconds (each packet waits delay plus a
uniform 0..jitter more, so packets can overtake each other as on a real
mobile link). The round trip is twice the delay. Writing a new line resets
the freeze deadline. Packets received during a freeze are dropped, not
queued. --seed makes loss and jitter reproducible (default 527).
"""
import argparse
import heapq
import random
import selectors
import socket
import time

p = argparse.ArgumentParser()
p.add_argument('--listen', type=int, required=True)
p.add_argument('--server', required=True, help='host:port')
p.add_argument('--control', required=True)
p.add_argument('--seed', type=int, default=527)
a = p.parse_args()
host, port = a.server.rsplit(':', 1)
server = (host, int(port))
front = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
front.bind(('0.0.0.0', a.listen))
back = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
back.connect(server)
sel = selectors.DefaultSelector()
sel.register(front, selectors.EVENT_READ)
sel.register(back, selectors.EVENT_READ)
rng = random.Random(a.seed)
client = None
previous = None
loss = delay = jitter = frozen_until = 0.0
pending = []
serial = 0
while True:
    now = time.monotonic()
    try:
        with open(a.control, encoding='ascii') as f:
            line = f.read().strip()
        if line != previous:
            fields = [float(x) for x in line.split()]
            loss, delay, freeze = fields[:3]
            jitter = fields[3] if len(fields) > 3 else 0.0
            frozen_until = now + freeze
            if freeze > 0:
                pending.clear()
            previous = line
    except (OSError, ValueError):
        pass
    while pending and pending[0][0] <= now:
        _, _, sock, data, dest = heapq.heappop(pending)
        if dest is None:
            sock.send(data)
        else:
            sock.sendto(data, dest)
    wait = min(0.05, max(0, pending[0][0] - now)) if pending else 0.05
    for key, _ in sel.select(wait):
        sock = key.fileobj
        data, peer = sock.recvfrom(65535)
        if sock is front:
            client = peer
            out, dest = back, None
        else:
            if client is None:
                continue
            out, dest = front, client
        if time.monotonic() < frozen_until or rng.random() < loss:
            continue
        serial += 1
        heapq.heappush(pending, (time.monotonic() + delay + rng.uniform(0, jitter), serial, out, data, dest))
