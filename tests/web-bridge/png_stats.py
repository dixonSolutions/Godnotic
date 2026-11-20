#!/usr/bin/env python3
"""Print JSON stats of a PNG screenshot: size, distinct colours (sampled),
luma mean and standard deviation, and the share of near-black pixels.
A drawn 3D world has many colours and real contrast; a black or one-colour
frame does not. Standard library only (8-bit RGB/RGBA, non-interlaced).

    python3 -I tests/web-bridge/png_stats.py shot.png
"""
import json
import struct
import sys
import zlib


def decode(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a PNG'
    at, idat, w = 8, b'', 0
    while at < len(data):
        n, kind = struct.unpack('>I4s', data[at:at + 8])
        body = data[at + 8:at + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
            assert depth == 8 and ctype in (2, 6) and not interlace, 'unsupported PNG'
            bpp = 3 if ctype == 2 else 4
        elif kind == b'IDAT':
            idat += body
        at += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return w, h, bpp, rows


def main():
    w, h, bpp, rows = decode(sys.argv[1])
    colours, lumas = set(), []
    for y in range(0, h, 4):
        row = rows[y]
        for x in range(0, w, 4):
            r, g, b = row[x * bpp:x * bpp + 3]
            colours.add((r >> 2, g >> 2, b >> 2))
            lumas.append(0.299 * r + 0.587 * g + 0.114 * b)
    mean = sum(lumas) / len(lumas)
    sd = (sum((v - mean) ** 2 for v in lumas) / len(lumas)) ** 0.5
    dark = sum(1 for v in lumas if v < 12) / len(lumas)
    print(json.dumps({'width': w, 'height': h, 'colours': len(colours), 'mean': round(mean, 1),
                      'sd': round(sd, 1), 'dark': round(dark, 3)}))


if __name__ == '__main__':
    main()
