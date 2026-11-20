#!/usr/bin/env python3
"""A tiny MQTT 3.1.1 broker over plain WebSocket, for the web relay tests.

    python3 -I tests/web-bridge/mqtt_broker.py [--port N] [--log FILE]

Stands in for a public broker (wss://broker.emqx.io:8084/mqtt) offline. Only
what the relay signaling uses: CONNECT with a will, SUBSCRIBE with + and #
wildcards, PUBLISH QoS 0 with retained messages, PINGREQ, DISCONNECT. Prints
"listening <port>" once ready. Standard library only.
"""
import argparse
import asyncio
import base64
import hashlib
import struct
import sys

GUID = b'258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
retained = {}
clients = set()
log_file = None


def log(*parts):
    if log_file:
        print(*parts, file=log_file, flush=True)


def matches(filt, topic):
    f, t = filt.split('/'), topic.split('/')
    for i, part in enumerate(f):
        if part == '#':
            return True
        if i >= len(t) or (part != '+' and part != t[i]):
            return False
    return len(f) == len(t)


def varint(n):
    out = bytearray()
    while True:
        b = n & 127
        n >>= 7
        out.append(b | (128 if n else 0))
        if not n:
            return bytes(out)


def mqtt_str(b):
    return struct.pack('>H', len(b)) + b


def publish_packet(topic, payload, retain):
    body = mqtt_str(topic.encode()) + payload
    return bytes([0x30 | (1 if retain else 0)]) + varint(len(body)) + body


class Client:
    def __init__(self, writer):
        self.writer = writer
        self.subs = []
        self.will = None
        self.id = '?'

    def send(self, data):
        # one unmasked binary WebSocket frame
        n = len(data)
        if n < 126:
            head = bytes([0x82, n])
        elif n < 65536:
            head = bytes([0x82, 126]) + struct.pack('>H', n)
        else:
            head = bytes([0x82, 127]) + struct.pack('>Q', n)
        try:
            self.writer.write(head + data)
        except Exception:
            pass


def route(topic, payload, retain):
    if retain:
        if payload:
            retained[topic] = payload
        else:
            retained.pop(topic, None)
    for c in list(clients):
        if any(matches(f, topic) for f in c.subs):
            c.send(publish_packet(topic, payload, False))


def handle_packet(c, ptype, flags, body):
    if ptype == 1:  # CONNECT
        at = 2 + struct.unpack('>H', body[:2])[0]
        cflags = body[at + 1]
        at += 4
        n = struct.unpack('>H', body[at:at + 2])[0]
        c.id = body[at + 2:at + 2 + n].decode()
        at += 2 + n
        if cflags & 0x04:
            n = struct.unpack('>H', body[at:at + 2])[0]
            wtopic = body[at + 2:at + 2 + n].decode()
            at += 2 + n
            n = struct.unpack('>H', body[at:at + 2])[0]
            c.will = (wtopic, body[at + 2:at + 2 + n], bool(cflags & 0x20))
        log('connect', c.id)
        c.send(b'\x20\x02\x00\x00')
    elif ptype == 3:  # PUBLISH
        n = struct.unpack('>H', body[:2])[0]
        topic = body[2:2 + n].decode()
        at = 2 + n + (2 if (flags >> 1) & 3 else 0)
        log('publish', c.id, topic, len(body) - at, 'retain' if flags & 1 else '')
        route(topic, body[at:], bool(flags & 1))
    elif ptype == 8:  # SUBSCRIBE
        pid = body[:2]
        at, granted, new = 2, bytearray(), []
        while at < len(body):
            n = struct.unpack('>H', body[at:at + 2])[0]
            new.append(body[at + 2:at + 2 + n].decode())
            at += 3 + n
            granted.append(0)
        c.subs += new
        log('subscribe', c.id, new)
        c.send(bytes([0x90, 2 + len(granted)]) + pid + bytes(granted))
        for topic, payload in list(retained.items()):
            if any(matches(f, topic) for f in new):
                c.send(publish_packet(topic, payload, True))
    elif ptype == 12:  # PINGREQ
        c.send(b'\xd0\x00')
    elif ptype == 14:  # DISCONNECT
        c.will = None
        return False
    return True


async def ws_frames(reader):
    """Yields (opcode, payload) of client frames."""
    while True:
        b0, b1 = await reader.readexactly(2)
        n = b1 & 127
        if n == 126:
            n = struct.unpack('>H', await reader.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack('>Q', await reader.readexactly(8))[0]
        mask = await reader.readexactly(4) if b1 & 128 else b'\0\0\0\0'
        data = bytearray(await reader.readexactly(n))
        for i in range(n):
            data[i] ^= mask[i & 3]
        yield b0 & 15, bytes(data)


async def serve(reader, writer):
    c = Client(writer)
    try:
        head = await reader.readuntil(b'\r\n\r\n')
        key = None
        for line in head.split(b'\r\n'):
            if line.lower().startswith(b'sec-websocket-key:'):
                key = line.split(b':', 1)[1].strip()
        if not key:
            writer.close()
            return
        accept = base64.b64encode(hashlib.sha1(key + GUID).digest())
        writer.write(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                     b'Sec-WebSocket-Protocol: mqtt\r\nSec-WebSocket-Accept: ' + accept + b'\r\n\r\n')
        clients.add(c)
        buf = b''
        async for op, data in ws_frames(reader):
            if op == 8:
                break
            if op == 9:
                writer.write(bytes([0x8A, len(data)]) + data)
                continue
            if op not in (0, 2):
                continue
            buf += data
            while len(buf) >= 2:
                n, shift, i = 0, 0, 1
                while i < len(buf) and i < 5:
                    n |= (buf[i] & 127) << shift
                    shift += 7
                    if not buf[i] & 128:
                        break
                    i += 1
                if i >= len(buf) or len(buf) < i + 1 + n:
                    break
                packet, buf = buf[:i + 1 + n], buf[i + 1 + n:]
                if not handle_packet(c, packet[0] >> 4, packet[0] & 15, packet[i + 1:]):
                    raise EOFError
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionError, EOFError, asyncio.LimitOverrunError):
        pass
    finally:
        clients.discard(c)
        if c.will:
            log('will', c.id, c.will[0])
            route(*c.will)
        log('gone', c.id)
        try:
            writer.close()
        except Exception:
            pass


async def main():
    global log_file
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=0)
    ap.add_argument('--log')
    a = ap.parse_args()
    if a.log:
        log_file = open(a.log, 'w')
    server = await asyncio.start_server(serve, '127.0.0.1', a.port)
    print('listening', server.sockets[0].getsockname()[1], flush=True)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
