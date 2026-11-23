#!/usr/bin/env python3
"""Collect small map pictures for the maps public servers are playing, for the `mapshots` release that
MapShots.sources (android .../fe/MapShots.kt) reads: one <map>.jpg per map, at most 400 KB.

For each server (from the masters, or --addr): ask getstatus for its map, then do the first 3 round trips of a join
(getchallenge, connect, the first fragments of the server's signon message, which carry its `curl --pak <url>` lines),
leave politely (clc_disconnect, three times, as the engine does), and read the map's picture out of the server's own
pack over HTTP Range: the end-of-central-directory, the central directory, then only the picture's entry (deflate
inflated here). A picture named for the map's first revision (sinister_full.jpg for sinister_full_r1) is taken as the
engine does (touch_zipshot.h tzs_find). Nothing else is downloaded: a few hundred KB per map.

  python3 scripts/mapshots-harvest.py -o OUT [--addr host:port ...] [--max N] [--skip-file maps.txt] [--dry-run]
  python3 scripts/mapshots-harvest.py --self-test     (no network: the zip and name logic)

Never run by hand against the public list from a metered link; the workflow .github/workflows/mapshots.yml runs it.
"""
import argparse, json, os, re, socket, struct, sys, time, urllib.request, zlib

MAX_BYTES = 400 * 1024
HDR = b"\xff\xff\xff\xff"
NETFLAG_DATA, NETFLAG_ACK, NETFLAG_EOM, NETFLAG_UNRELIABLE, NETFLAG_CTL = 0x10000, 0x20000, 0x80000, 0x100000, 0x80000000
MASTERS = [("dpmaster.deathmask.net", 27950), ("dpmaster.tchr.no", 27950)]
UA = "Godnotic-mapshots/1 (+https://github.com/dixonSolutions/Godnotic)"


# ---- names: the picture of a map's first revision ---------------------------------------------------------------
def name_chain(m):
    """The map's name, then cut back at each '_' (sinister_full_r1 -> sinister_full -> sinister), at least 4 letters."""
    out = [m]
    for i in range(len(m) - 1, 4, -1):
        if m[i] == "_":
            out.append(m[:i])
    return out


# ---- zip over Range ----------------------------------------------------------------------------------------------
def parse_eocd(tail, total):
    i = tail.rfind(b"PK\x05\x06")
    while i >= 0:
        size, off = struct.unpack("<I", tail[i + 12:i + 16])[0], struct.unpack("<I", tail[i + 16:i + 20])[0]
        if i + 22 + struct.unpack("<H", tail[i + 20:i + 22])[0] == len(tail) and 0 < size <= 2 * 1024 * 1024 and off + size <= total:
            return off, size
        i = tail.rfind(b"PK\x05\x06", 0, i)
    return None


def central_entries(cd):
    p = 0
    while p + 46 <= len(cd) and cd[p:p + 4] == b"PK\x01\x02":
        flags, method = struct.unpack("<HH", cd[p + 8:p + 12])
        csz, usz = struct.unpack("<II", cd[p + 20:p + 28])
        nl, el, cl = struct.unpack("<HHH", cd[p + 28:p + 34])
        off = struct.unpack("<I", cd[p + 42:p + 46])[0]
        yield cd[p + 46:p + 46 + nl].decode("latin1"), method, flags, csz, usz, off
        p += 46 + nl + el + cl


def pick_entry(entries, map_name):
    """maps/<name>.jpg|png (then .tga) for the map's name chain; a picture the size of a few hundred KB."""
    ents = {e[0].lower(): e for e in entries}
    for ext in (".jpg", ".png", ".tga"):
        for base in name_chain(map_name):
            for d in ("maps/", "levelshots/"):
                e = ents.get(d + base.lower() + ext)
                if e and not e[2] & 1 and e[1] in (0, 8) and 0 < e[3] <= 4 * 1024 * 1024:
                    return e
    return None


def inflate(entry, raw):
    name, method, _f, csz, usz, _o = entry
    return raw if method == 0 else zlib.decompressobj(-15).decompress(raw, usz + 1)


def http_range(url, rng, timeout=8):
    req = urllib.request.Request(url, headers={"Range": rng, "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read(4 * 1024 * 1024 + 1)
        cr = r.headers.get("Content-Range", "")
        total = int(cr.rsplit("/", 1)[1]) if "/" in cr and cr.rsplit("/", 1)[1].isdigit() else int(r.headers.get("Content-Length", len(data)))
        return r.status, data, total


def picture_from_pack(url, map_name, fetch=http_range):
    """(file name in the pack, its bytes) or None; every request is a Range request, the whole pack is never read."""
    st, tail, total = fetch(url, "bytes=-65536")
    if st != 206:
        return None  # a server that ignores Range would send the whole pack: not read
    loc = parse_eocd(tail, total)
    if not loc:
        return None
    off, size = loc
    start = total - len(tail)
    if off >= start:
        cd = tail[off - start:off - start + size]
    else:
        st, cd, _t = fetch(url, "bytes=%d-%d" % (off, off + size - 1))
        if st != 206:
            return None
    e = pick_entry(list(central_entries(cd)), map_name)
    if not e:
        return None
    name, method, _f, csz, usz, eoff = e
    st, blob, _t = fetch(url, "bytes=%d-%d" % (eoff, eoff + 30 + len(name) + 512 + csz))
    if st != 206 or blob[:4] != b"PK\x03\x04":
        return None
    nl, el = struct.unpack("<HH", blob[26:30])
    raw = blob[30 + nl + el:30 + nl + el + csz]
    return name, inflate(e, raw)


def to_jpeg(name, data):
    """The bytes to publish: a JPEG of at most MAX_BYTES (a PNG/TGA/big JPEG is converted with Pillow when there is one)."""
    if data[:2] == b"\xff\xd8" and len(data) <= MAX_BYTES:
        return data
    try:
        from PIL import Image
        import io
        im = Image.open(io.BytesIO(data)).convert("RGB")
        im.thumbnail((800, 450))
        for q in (85, 75, 65, 50):
            b = io.BytesIO()
            im.save(b, "JPEG", quality=q, optimize=True)
            if b.tell() <= MAX_BYTES:
                return b.getvalue()
    except Exception:
        pass
    return None


# ---- the first round trips of a join ----------------------------------------------------------------------------
def recv_until(s, pred, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        s.settimeout(max(0.05, end - time.monotonic()))
        try:
            d, _ = s.recvfrom(65535)
        except socket.timeout:
            return None
        if pred(d):
            return d
    return None


def probe_packs(addr, timeout=4.0, sock=None):
    """The `curl --pak` URLs a server's signon message asks for, found in its first fragments, or [] (it is left again)."""
    s = sock or socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(addr)
    urls, buf = [], b""
    try:
        s.send(HDR + b"getchallenge")
        d = recv_until(s, lambda d: d[:4] == HDR and d[4:14] == b"challenge ", timeout)
        if not d:
            return []
        chal = re.split(rb"[\0\s]", d[14:], maxsplit=1)[0]
        s.send(HDR + b"connect\\protocol\\darkplaces 3\\protocols\\DP7 DP6 DP5 DP4 DP3 RMQ QW QUAKE\\challenge\\" + chal)
        if not recv_until(s, lambda d: d[:4] == HDR and d[4:10] == b"accept", timeout):
            return []
        seen = {}
        end = time.monotonic() + timeout
        def head():
            out, nxt = b"", None
            for q in sorted(seen):
                if nxt is not None and q != nxt:
                    break  # a gap ends the head of the message
                out += seen[q]
                nxt = q + 1
            return out
        while time.monotonic() < end and len(buf) < 8192 and b"curl --finish_autodownload" not in buf:
            try:
                s.settimeout(min(0.8, max(0.05, end - time.monotonic())) if seen else max(0.05, end - time.monotonic()))
                d = s.recv(65535)
            except socket.timeout:
                break  # nothing more for a moment after the first fragments: the head of the message is here
            if len(d) < 8 or d[:4] == HDR:
                continue
            flags, seq = struct.unpack(">II", d[:8])
            if flags & NETFLAG_DATA:
                s.send(struct.pack(">II", NETFLAG_ACK | 8, seq))
                if seq not in seen:
                    seen[seq] = d[8:(flags & 0xFFFF)]
                    buf = head()
        buf = head()
        for m in re.finditer(rb"curl --pak ([^\n\0]*?)(https?://[^\s\0\n]+)\n", buf):
            u = m.group(2).decode("latin1")
            if u not in urls:
                urls.append(u)
    finally:
        # leave as the engine does: clc_disconnect, three times
        for i in range(3):
            try:
                s.send(struct.pack(">II", NETFLAG_UNRELIABLE | 9, i) + b"\x02")
            except OSError:
                pass
        s.close()
    return urls


def query_masters():
    addrs = set()
    for h, p in MASTERS:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(3)
            s.sendto(HDR + b"getservers Xonotic 3 empty full", (socket.gethostbyname(h), p))
            t = time.time()
            while time.time() - t < 4:
                d, _ = s.recvfrom(65535)
                d = d[len(HDR + b"getserversResponse"):]
                for i in range(0, len(d) - 6, 7):
                    if d[i] == 0x5C:
                        addrs.add((".".join(str(b) for b in d[i + 1:i + 5]), d[i + 5] * 256 + d[i + 6]))
        except (OSError, socket.timeout):
            pass
    return sorted(addrs)


def status(addr):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(2)
    try:
        s.sendto(HDR + b"getstatus", addr)
        d, _ = s.recvfrom(65535)
        p = d.decode("latin1").split("\n")[1].split("\\")[1:]
        return dict(zip(p[0::2], p[1::2]))
    except (OSError, socket.timeout, IndexError):
        return {}
    finally:
        s.close()


def harvest_one(addr, map_name, out, fetch=http_range, packs=None):
    """The picture of map_name from the server at addr: path written, or None."""
    for url in (packs if packs is not None else probe_packs(addr))[:8]:
        base = os.path.basename(url).lower()
        if map_name.lower() not in base and not any(b.lower() in base for b in name_chain(map_name)):
            continue  # the pack that carries this map is named for it
        try:
            got = picture_from_pack(url, map_name, fetch)
        except Exception:
            got = None
        if got:
            jpg = to_jpeg(*got)
            if jpg:
                path = os.path.join(out, map_name + ".jpg")
                open(path, "wb").write(jpg)
                return path, url
    return None


def self_test():
    assert name_chain("sinister_full_r1")[:2] == ["sinister_full_r1", "sinister_full"]
    assert name_chain("ab_c") == ["ab_c"]
    import io, zipfile
    z = io.BytesIO()
    with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as f:
        f.writestr("maps/sinister_full.jpg", b"\xff\xd8\xff\xe0" + bytes(range(256)) * 8)
        f.writestr("maps/other.jpg", b"\xff\xd8\xff\xe0" + b"x" * 100)
        f.writestr("maps/sinister_full_r1.bsp", b"B" * 5000)
    pack = z.getvalue()
    calls = []

    def fake(url, rng, timeout=8):
        calls.append(rng)
        if rng.startswith("bytes=-"):
            n = int(rng[7:])
            return 206, pack[-n:], len(pack)
        a, b = rng[6:].split("-")
        return 206, pack[int(a):int(b) + 1], len(pack)
    got = picture_from_pack("http://x/sinister_full_r1.pk3", "sinister_full_r1", fake)
    assert got and got[0] == "maps/sinister_full.jpg" and got[1].startswith(b"\xff\xd8"), got
    assert all(c.startswith("bytes=") for c in calls) and len(calls) <= 3, calls
    assert picture_from_pack("http://x/a.pk3", "nomap", fake) is None
    assert picture_from_pack("http://x/a.pk3", "other_map_r2", lambda u, r, timeout=8: (200, pack, len(pack))) is None  # no Range: refuse
    assert to_jpeg("a", b"\xff\xd8" + b"x" * 10) == b"\xff\xd8" + b"x" * 10
    assert to_jpeg("a", b"\xff\xd8" + b"x" * (MAX_BYTES + 1)) is None or True  # Pillow may shrink it; never more than MAX_BYTES
    big = to_jpeg("a", b"\xff\xd8" + b"x" * (MAX_BYTES + 1))
    assert big is None or len(big) <= MAX_BYTES
    import tempfile
    d = tempfile.mkdtemp()
    r = harvest_one(("1.2.3.4", 1), "sinister_full_r1", d, fake, packs=["http://x/other.pk3", "http://x/sinister_full_r1.pk3"])
    assert r and open(r[0], "rb").read().startswith(b"\xff\xd8") and r[1].endswith("sinister_full_r1.pk3"), r
    assert harvest_one(("1.2.3.4", 1), "sinister_full_r1", d, fake, packs=["http://x/unrelated.pk3"]) is None
    print("mapshots-harvest self-test: PASS")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", default="mapshots-out")
    ap.add_argument("--addr", action="append", default=[])
    ap.add_argument("--max", type=int, default=60)
    ap.add_argument("--skip-file", help="maps (one per line) that need no picture: the ones in the stock data")
    ap.add_argument("--dry-run", action="store_true", help="list what would be probed")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--delay", type=float, default=1.0, help="seconds between servers")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    skip = set(open(a.skip_file).read().split()) if a.skip_file else set()
    os.makedirs(a.o, exist_ok=True)
    servers = [(h, int(p)) for h, p in (x.rsplit(":", 1) for x in a.addr)] or query_masters()
    done, index, n = set(), {}, 0
    for addr in servers:
        if n >= a.max:
            break
        m = status(addr).get("mapname", "")
        if not m or m in skip or m in done or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", m):
            continue
        n += 1
        if a.dry_run:
            print("would probe %s:%d for %s" % (addr[0], addr[1], m))
            continue
        t = time.time()
        r = harvest_one(addr, m, a.o)
        done.add(m)
        print("%-24s %-22s %s" % (m, "%s:%d" % addr, ("%d bytes %.1fs %s" % (os.path.getsize(r[0]), time.time() - t, r[1])) if r else "no picture"), flush=True)
        if r:
            index[m] = {"server": "%s:%d" % addr, "pack": r[1], "bytes": os.path.getsize(r[0])}
        time.sleep(a.delay)
    json.dump(index, open(os.path.join(a.o, "index.json"), "w"), indent=1, sort_keys=True)
    print("%d pictures for %d maps tried" % (len(index), n))


if __name__ == "__main__":
    main()
