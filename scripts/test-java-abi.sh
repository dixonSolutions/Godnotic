#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The engine's contract with the Java side it runs under (java_abi, andysu_swap.h).
#
# Android swaps the engine (libmain) and its game data into the running process even when a
# release also changed the Java code: the new engine then runs under the previous release's Java
# code and resident libraries until the next start. The loader refuses that swap when the two
# engines' java_abi differ, so java_abi has to change whenever what the engine needs from the Java
# side changes. This test fingerprints that surface and fails when it moved without a bump:
#   - JNI the engine calls (FindClass / Get*MethodID / Get*FieldID literals, JNIEXPORT names);
#   - the resident libnativeui entry points the engine looks up, and libnativeui's FE_VERSION;
#   - the protocol and file-format versions both sides read (front end, UI bridge, the Updates,
#     update and data status files);
#   - the engine's own JNI entry declared by XonoticActivity (served by the loader).
# When it fails: if the change is backward compatible (an old Java side still works with the new
# engine, e.g. the engine only gained an optional lookup it tolerates missing), update the
# fingerprint; otherwise bump GODNOTIC_JAVA_ABI as well. Both live in
# android/app/src/main/cpp/andysu/java_abi.h.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I - <<'PY'
import hashlib, re, sys
from pathlib import Path

def read(p):
    return Path(p).read_text(errors='replace')

entries = set()
engine_c = sorted(Path('engine/darkplaces').glob('*.c')) + [Path('android/app/src/main/cpp/nativeui/engine.c')]
for f in engine_c:
    s = read(f)
    for m in re.finditer(r'\b(FindClass|GetMethodID|GetStaticMethodID|GetFieldID|GetStaticFieldID)\s*\(([^;]*)\)', s):
        lits = re.findall(r'"([^"]*)"', m.group(2))
        if lits:
            entries.add('jni %s %s' % (m.group(1), ' '.join(lits)))
    for m in re.finditer(r'JNIEXPORT\s+\w+\s+JNICALL\s+(Java_\w+)', s):
        entries.add('export ' + m.group(1))
    for m in re.finditer(r'dlsym\s*\(\s*\w+\s*,\s*"([^"]+)"', s):
        entries.add('dlsym ' + m.group(1))
bridge = read('android/app/src/main/cpp/nativeui/bridge.c')
for m in re.finditer(r'#define\s+(FE_VERSION)\s+(\S+)', bridge):
    entries.add('define nativeui %s %s' % m.groups())
for f, names in (('engine/darkplaces/touch_frontend.h', ['TOUCHFE_PROTOCOL']),
                 ('engine/darkplaces/touch_uib.h', ['TOUCHUIB_PROTOCOL'])):
    s = read(f)
    for n in names:
        m = re.search(r'#define\s+%s\s+(\S+)' % n, s)
        if not m:
            sys.exit('test-java-abi: %s not found in %s' % (n, f))
        entries.add('define %s %s' % (n, m.group(1)))
java = Path('android/app/src/main/java/io/github/dixonsolutions/godnotic')
for f in ('AndySUState.java', 'UpdateBridge.java', 'DataStatus.java', 'DataManifest.java'):
    m = re.search(r'static final int FORMAT\s*=\s*(\d+)', read(java / f))
    if not m:
        sys.exit('test-java-abi: no FORMAT in ' + f)
    entries.add('format %s %s' % (f, m.group(1)))
qh = read('engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_update_util.qh')
for m in re.finditer(r'const float (TOUCH_\w+_FORMAT)\s*=\s*(\d+)', qh):
    entries.add('format qc %s %s' % m.groups())
# The engine's own JNI entry (now served by the loader and forwarded to the running engine).
# Other natives (libnativeui, the loader, the Rust core) pair with the Java code they ship with.
s = read(java / 'XonoticActivity.java')
for m in re.finditer(r'^[ \t]*[^/*\n]*\bnative\b[^;{=\n]*\([^)]*\)\s*;', s, re.M):
    entries.add('native XonoticActivity ' + ' '.join(m.group(0).split()))

fingerprint = hashlib.sha256('\n'.join(sorted(entries)).encode()).hexdigest()
header = read('android/app/src/main/cpp/andysu/java_abi.h')
abi = re.search(r'#define\s+GODNOTIC_JAVA_ABI\s+(\d+)', header)
recorded = re.search(r'#define\s+GODNOTIC_JAVA_ABI_FINGERPRINT\s+"([0-9a-f]{64})"', header)
if not abi or not recorded:
    sys.exit('test-java-abi: java_abi.h needs GODNOTIC_JAVA_ABI and GODNOTIC_JAVA_ABI_FINGERPRINT')
engine = read('android/app/src/main/cpp/andysu/andysu_engine.c')
desc = re.search(r'andysu_engine_desc\s*=\s*\{([^}]*)\}', engine)
if not desc or desc.group(1).split(',')[2].strip() != 'GODNOTIC_JAVA_ABI':
    sys.exit('test-java-abi: andysu_engine.c must report GODNOTIC_JAVA_ABI as its java_abi, not a fixed number '
             '(a constant lets an engine run under any Java side)')
if recorded.group(1) != fingerprint:
    print('\n'.join(sorted(entries)))
    sys.exit('test-java-abi: the engine/Java contract changed (fingerprint %s, recorded %s). If an older Java side '
             'cannot run the new engine, bump GODNOTIC_JAVA_ABI (now %s); then record the new fingerprint in '
             'android/app/src/main/cpp/andysu/java_abi.h.' % (fingerprint, recorded.group(1), abi.group(1)))
print('java_abi %s: contract fingerprint matches (%d entries)' % (abi.group(1), len(entries)))
PY
