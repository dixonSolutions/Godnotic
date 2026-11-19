#!/usr/bin/env python3
"""Bidirectional UDP relay for network tests without tc.

The control file holds one line: loss fraction, one-way delay seconds, freeze
seconds and, optionally, jitter seconds (each packet waits delay plus a
uniform 0..jitter more). Packets keep their order in each direction, as on
a real link whose queue grows and shrinks; --reorder lets them overtake each
other instead (a much harsher link). The round trip is twice the delay. Writing a new line resets
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
p.add_argument('--reorder', action='store_true')
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
last_due = {}
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
        try:
            if dest is None:
                sock.send(data)
            else:
                sock.sendto(data, dest)
        except OSError:
            pass  # a peer not listening (ICMP unreachable) is a lost packet
    wait = min(0.05, max(0, pending[0][0] - now)) if pending else 0.05
    for key, _ in sel.select(wait):
        sock = key.fileobj
        try:
            data, peer = sock.recvfrom(65535)
        except OSError:
            continue  # an earlier send was refused: nothing to relay
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
        due = time.monotonic() + delay + rng.uniform(0, jitter)
        if not a.reorder:
            due = max(due, last_due.get(out, 0.0))
            last_due[out] = due
        heapq.heappush(pending, (due, serial, out, data, dest))
