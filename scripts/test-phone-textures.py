#!/usr/bin/env python3
"""Every texture and skybox a shipped map names must be in the pack set.

    scripts/test-phone-textures.py PHONEDIR
    scripts/test-phone-textures.py --self-test

PHONEDIR is an output directory of scripts/repack-phone-assets.py (its
phone-manifest.json says which packs exist). The audit mounts every pack the
manifest lists, in install_as order as the engine does, and for each
maps/<map>.bsp in the set reads

  * the BSP's texture lump: every name it uses must resolve to a file in the
    set -- dds/<name>.ktx or .dds (what the engine asks for first), a source
    image textures/<name>.<ext>, or a shader of that name in scripts/*.shader
    whose own images (map, clampmap, animmap) all resolve;
  * the worldspawn's "sky" key: all six sides must exist, tried the way
    r_sky.c tries them (suffix set %s_%s, %s%s, env/%s%s, gfx/env/%s%s).

A repack that drops, renames or converts a texture or skybox into something
the engine cannot load fails here (the player would see the missing-texture
checkerboard and a black sky) instead of only in game.

--self-test writes a small made-up set in a temporary directory and checks
that the audit accepts it, then that it names a removed texture and a removed
sky side, so the check itself runs in CI without a data set.
"""

import argparse
import json
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

# the extensions image.c tries for a plain image, dds last as it is the only
# one the engine loads through R_LoadTextureDDSFile
IMAGE_EXT = ("tga", "jpg", "jpeg", "png", "dds")
# r_sky.c's three suffix groups, in the order it tries them
SKY_SUFFIXES = (("px", "nx", "py", "ny", "pz", "nz"),
                ("posx", "negx", "posy", "negy", "posz", "negz"),
                ("rt", "lf", "bk", "ft", "up", "dn"))
# IBSP 46 (Q3, what Xonotic ships): 17 lumps, entities 0 and shaders 1;
# the shader lump is a flat array -- no count -- of dshader_t records of
# char name[64]; int surfaceFlags; int contents.
Q3SHADER_RECORD = 72
Q3_LUMPS = 17
# Q1 (29) keeps a dmiptex lump: a count, then offsets to name[16] records.
Q1_LUMPS = 15
Q1_LUMP_TEXTURES = 2


class PackSet:
    """The entries of every pack, as the engine sees them after mounting."""

    def __init__(self, phondir):
        with open(os.path.join(phondir, "phone-manifest.json")) as f:
            manifest = json.load(f)
        self.packs = [r for r in sorted(manifest["packs"], key=lambda r: r["install_as"])
                      if "nexcompat" not in r["install_as"]]
        self.entries = {}
        self.zips = {}
        for rec in self.packs:
            path = os.path.join(phondir, rec["file"])
            z = zipfile.ZipFile(path)
            self.zips[rec["file"]] = z
            for info in z.infolist():
                if not info.is_dir():
                    self.entries[info.filename.lower()] = (z, info)
        self.shaders = self._read_shaders()

    @property
    def files(self):
        return self.entries.keys()

    def has(self, name):
        return name.lower() in self.entries

    def read(self, name):
        z, info = self.entries[name.lower()]
        return z.read(info)

    def _read_shaders(self):
        """{name: {"images": [...], "sky": "env/x/x" or None}} from every
        scripts/*.shader the set carries."""
        out = {}
        for name in sorted(self.entries):
            if not (name.startswith("scripts/") and name.endswith(".shader")):
                continue
            out.update(parse_shader_file(self.read(name).decode("latin-1", "replace")))
        return out


SHADER_NAME = re.compile(r"^[A-Za-z0-9_./\\-]+$")
# materials the engine itself knows (model_shared.c compares the name): the
# q3map2 marker for a surface with no texture of its own
ENGINE_NAMES = {"noshader"}


def parse_shader_file(text):
    """{name: body} for each material in a .shader file. A material's stages
    are blocks of their own inside its block, so the body is found by brace
    depth, not by a non-nesting pattern."""
    lines = [line.split("//", 1)[0].strip() for line in text.splitlines()]
    out, i = {}, 0
    while i < len(lines):
        name = lines[i]
        opens_same = name.endswith("{")
        bare = name[:-1].strip() if opens_same else name
        if SHADER_NAME.match(bare) and (opens_same or (i + 1 < len(lines) and lines[i + 1].startswith("{"))):
            depth, body, j = 0, [], i + (0 if opens_same else 1)
            while j < len(lines):
                depth += lines[j].count("{") - lines[j].count("}")
                body.append(lines[j])
                j += 1
                if depth <= 0:
                    break
            out.setdefault(bare.lower(), parse_shader_body("\n".join(body)))
            i = j
        else:
            i += 1
    return out


def parse_shader_body(body):
    images, sky = [], None
    for line in body.splitlines():
        line = line.split("//", 1)[0].strip()
        if not line:
            continue
        word = line.split()
        op = word[0].lower()
        if op in ("map", "clampmap") and len(word) > 1:
            images.append(word[1].strip('"'))
        elif op == "animmap" and len(word) > 2:
            images += [w.strip('"') for w in word[2:]]
        elif op == "skyparms" and len(word) > 1:
            far = word[1].strip('"')
            if far != "-" and not far.isdigit():
                sky = far
    return {"images": [i for i in images if i and not i.startswith("$") and i != "-"], "sky": sky}


def bsp_header(data):
    """(version, lumps) or None when this is not a BSP the engine loads."""
    if data[:4] != b"IBSP":
        return None
    version, = struct.unpack_from("<i", data, 4)
    count = Q3_LUMPS if version >= 46 else Q1_LUMPS
    if len(data) < 8 + 8 * count:
        return None
    return version, [struct.unpack_from("<ii", data, 8 + 8 * i) for i in range(count)]


def bsp_textures(data):
    """The texture names the BSP uses, or None when the file cannot say."""
    header = bsp_header(data)
    if header is None:
        return None
    version, lumps = header
    out = []
    if version >= 46:
        off, length = lumps[1]  # Q3 LUMP_SHADERS
        if off < 0 or length < 0 or off + length > len(data):
            return None
        for k in range(length // Q3SHADER_RECORD):
            p = off + k * Q3SHADER_RECORD
            name = data[p:p + 64].split(b"\0")[0].decode("latin-1")
            if name:
                out.append(name)
        return out
    off, _ = lumps[Q1_LUMP_TEXTURES]
    if off < 0 or off + 4 > len(data):
        return None
    count, = struct.unpack_from("<i", data, off)
    for k in range(count):
        p = off + 4 + 4 * k
        if p + 4 > len(data):
            return None
        dataofs, = struct.unpack_from("<i", data, p)
        if dataofs < 0:
            continue
        q = off + dataofs
        name = data[q:q + 16].split(b"\0")[0].decode("latin-1")
        if name:
            out.append(name)
    return out


def bsp_sky(data):
    """The worldspawn's "sky" value, or None."""
    header = bsp_header(data)
    if header is None:
        return None
    off, length = header[1][0]  # LUMP_ENTITIES
    block = data[off:off + length].decode("latin-1")
    m = re.search(r"\{([^{}]*)\}", block)
    if not m:
        return None
    kv = dict(re.findall(r'"([^"]+)"\s+"([^"]*)"', m.group(1)))
    return kv.get("sky") or None


def side_names(sky, suffix):
    """The four forms r_sky.c builds for one side of a skybox."""
    return ("%s_%s" % (sky, suffix), "%s%s" % (sky, suffix),
            "env/%s%s" % (sky, suffix), "gfx/env/%s%s" % (sky, suffix))


def sky_missing(ps, sky):
    """The sides of `sky` the set cannot supply (empty when all six exist)."""
    for group in SKY_SUFFIXES:
        missing = []
        for suffix in group:
            if not any(ps.has("%s.%s" % (name, ext))
                       for name in side_names(sky, suffix) for ext in IMAGE_EXT + ("ktx",)):
                missing.append(suffix)
        if not missing:
            return []
    return missing


def strip_image_ext(name):
    """The name without a trailing image extension: the engine strips it too
    (Image_StripImageExtension) before asking for dds/<name>.dds, so a shader's
    `map textures/x/y.tga` is the file dds/textures/x/y.ktx."""
    base = name.rsplit("/", 1)[-1]
    if "." in base and base.rsplit(".", 1)[1].lower() in IMAGE_EXT + ("ktx",):
        return name.rsplit(".", 1)[0]
    return name


def resolver(ps):
    seen = set()

    def resolve(name, depth=0):
        """'dds', 'image', 'shader', or None when the set has no such file."""
        key = strip_image_ext(name.lower())
        if key in ENGINE_NAMES:
            return "engine"  # the engine's own marker, nothing to load
        for ext in ("ktx", "dds"):
            if ps.has("dds/%s.%s" % (key, ext)):
                return "dds"
        # the BSP names a material, which is already a path: the engine asks
        # for that path as it is, and only a bare name gets textures/ put in
        # front of it by the shader
        bare = key if key.startswith(("textures/", "models/", "gfx/", "env/")) else "textures/" + key
        for ext in IMAGE_EXT:
            if ps.has("%s.%s" % (key, ext)) or ps.has("%s.%s" % (bare, ext)):
                return "image"
        shader = ps.shaders.get(key)
        if shader is None or depth > 4 or key in seen:
            return None
        if not shader["images"]:
            return None
        seen.add(key)
        try:
            return "shader" if all(resolve(i, depth + 1) for i in shader["images"]) else None
        finally:
            seen.discard(key)

    return resolve


def audit(phondir):
    """[(map, what, detail)] of everything the set cannot supply."""
    ps = PackSet(phondir)
    resolve = resolver(ps)
    problems = []
    maps = sorted(n for n in ps.files
                  if n.startswith("maps/") and n.endswith(".bsp") and n.count("/") == 1)
    if not maps:
        raise SystemExit("no maps/*.bsp in %s" % phondir)
    for name in maps:
        data = ps.read(name)
        textures = bsp_textures(data)
        # a BSP whose texture lump cannot be read is a failure, not a pass
        if textures is None:
            problems.append((name, "unreadable", "no IBSP texture lump"))
            continue
        if not textures:
            problems.append((name, "unreadable", "texture lump is empty"))
            continue
        for tex in textures:
            if resolve(tex) is None:
                problems.append((name, "texture", tex))
        sky = bsp_sky(data)
        if sky:
            missing = sky_missing(ps, sky)
            if missing:
                problems.append((name, "sky", "%s (no side: %s)" % (sky, ", ".join(missing))))
            shader = ps.shaders.get(sky.lower())
            if shader and shader["sky"]:
                missing = sky_missing(ps, shader["sky"])
                if missing:
                    problems.append((name, "sky shader", "%s (no side: %s)" % (shader["sky"], ", ".join(missing))))
    return len(maps), problems


# ------------------------------------------------------------------ self-test

def write_bsp(path, textures, sky):
    """A minimal IBSP 46 laid out as the engine reads it: entities in lump 0,
    the shader array in lump 1, every other lump empty."""
    entities = b'{\n"classname" "worldspawn"\n"sky" "%s"\n}\n\0' % sky.encode()
    shaders = b"".join(t.encode().ljust(64, b"\0") + struct.pack("<ii", 0, 0) for t in textures)
    off_ent = 8 + 8 * Q3_LUMPS
    off_shader = off_ent + len(entities)
    lumps = [(0, 0)] * Q3_LUMPS
    lumps[0] = (off_ent, len(entities))
    lumps[1] = (off_shader, len(shaders))
    data = b"IBSP" + struct.pack("<i", 46) + b"".join(struct.pack("<ii", *l) for l in lumps)
    data += entities + shaders
    with open(path, "wb") as f:
        f.write(data)


def self_test():
    tmp = tempfile.mkdtemp(prefix="phone-textures-")
    phone = os.path.join(tmp, "phone")
    os.makedirs(phone)
    bsp = os.path.join(tmp, "one.bsp")
    write_bsp(bsp, ["textures/a/wall", "textures/a/floor"], "env/test/test")
    # the map extension is stripped, so this resolves to dds/.../floorimage.ktx
    shader = b"textures/a/floor\n{\n\tmap textures/a/floorimage.tga\n}\n"
    env = ["env/test/test_%s.jpg" % s for s in SKY_SUFFIXES[2]]
    data_pack, maps_pack = "xonotic-20260101-data-phone.pk3", "xonotic-20260101-maps-phone.pk3"
    packs = {data_pack: {},
             maps_pack: {"maps/one.bsp": open(bsp, "rb").read(),
                                                 "dds/textures/a/wall.ktx": b"\xabKTX 11\xbb\r\n\x1a\n" + bytes(64),
                                                 "dds/textures/a/floorimage.ktx": b"\xabKTX 11\xbb\r\n\x1a\n" + bytes(64),
                                                 "scripts/a.shader": shader}}
    for name in env:
        packs[maps_pack][name] = b"\xff\xd8\xff"
    records = []
    for fname, entries in packs.items():
        with zipfile.ZipFile(os.path.join(phone, fname), "w") as z:
            for n, d in entries.items():
                z.writestr(n, d)
        records.append({"file": fname, "install_as": fname.replace("-phone", "")})
    with open(os.path.join(phone, "phone-manifest.json"), "w") as f:
        json.dump({"format": 1, "packs": records}, f)

    maps, problems = audit(phone)
    assert maps == 1 and not problems, problems
    print("ok: a complete set passes (%d map)" % maps)

    # a texture and a sky side the pack lost: the audit must name both
    lost = os.path.join(phone, maps_pack)
    with zipfile.ZipFile(lost) as z:
        keep = {i.filename: z.read(i) for i in z.infolist()
                if i.filename not in ("env/test/test_up.jpg", "dds/textures/a/wall.ktx")}
    with zipfile.ZipFile(lost, "w") as z:
        for n, d in keep.items():
            z.writestr(n, d)
    _, problems = audit(phone)
    kinds = {(what, detail) for _, what, detail in problems}
    assert ("texture", "textures/a/wall") in kinds, problems
    assert any(what == "sky" and "up" in detail for what, detail in kinds), problems
    print("ok: a dropped texture and a dropped sky side are both reported")
    shutil.rmtree(tmp)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("phonedir", nargs="?", help="output directory of repack-phone-assets.py")
    ap.add_argument("--self-test", action="store_true", help="audit a made-up set, no data needed")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.phonedir:
        ap.error("PHONEDIR or --self-test")
    maps, problems = audit(args.phonedir)
    if problems:
        by = {}
        for m, what, detail in problems:
            by.setdefault(m, []).append("%s %s" % (what, detail))
        for m in sorted(by):
            print("FAIL %s: %d missing" % (m, len(by[m])))
            for line in by[m][:10]:
                print("    " + line)
        print("FAIL: %d references the pack set cannot supply" % len(problems))
        sys.exit(1)
    print("ok: every texture and skybox of %d maps is in the set" % maps)


if __name__ == "__main__":
    main()
