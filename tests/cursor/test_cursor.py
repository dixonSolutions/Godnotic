#!/usr/bin/env python3
"""Headless check that a mouse brings the cursor back after a finger.

Runs the engine with SDL_VIDEODRIVER=offscreen and injects SDL events through a
small LD_PRELOAD shim (tests/cursor/inject.c): a direct finger, a mouse move, a
direct finger again, then an unclassified touch device 50 ms before a mouse
move. The engine prints "POINTER 1" / "POINTER 0" on every vid_pointer_mouse
change when vid_pointer_debug is on, and this asserts the order.

Expected with the fix: 0 (finger), 1 (mouse), 0 (finger), 1 (mouse after an
unknown device that must not count as a finger).
Without the fix the last 1 is missing: the unknown device refreshes the touch
timestamp and the 0.25 s quiet window swallows the mouse.

Set ENGINE and BASEDIR. Needs sdl2-config and a C compiler.
"""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
engine = os.environ.get('ENGINE', str(root / 'build/bin/xonotic'))
basedir = os.environ.get('BASEDIR', '')
if not basedir:
    raise SystemExit('set BASEDIR to a Xonotic basedir (data/*.pk3)')

RUN_SECONDS = 14

with tempfile.TemporaryDirectory(prefix='godnotic-cursor-') as tmpdir:
    tmp = Path(tmpdir)
    (tmp / 'data').mkdir()
    cflags = subprocess.check_output(['sdl2-config', '--cflags'], text=True).split()
    shim = tmp / 'inject.so'
    subprocess.run(['cc', '-shared', '-fPIC', *cflags, str(root / 'tests/cursor/inject.c'),
                    '-o', str(shim), '-ldl'], check=True)
    env = dict(os.environ)
    env.update(SDL_VIDEODRIVER='offscreen', SDL_AUDIODRIVER='dummy', LD_PRELOAD=str(shim))
    env.pop('DISPLAY', None)
    env.pop('WAYLAND_DISPLAY', None)
    cmd = [engine, '-xonotic', '-nosound', '-basedir', basedir, '-userdir', str(tmp),
           '+vid_fullscreen', '0', '+vid_touchscreen_mode', '2', '+vid_touchscreen', '1',
           '+vid_pointer_debug', '1']
    try:
        out = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, timeout=RUN_SECONDS).stdout
    except subprocess.TimeoutExpired as exc:
        out = (exc.stdout or b'').decode() if isinstance(exc.stdout, bytes) else (exc.stdout or '')

lines = [l.strip() for l in out.splitlines() if l.strip().startswith(('INJECT ', 'POINTER '))]
print('\n'.join(lines))
seen = [int(m.group(1)) for l in lines if (m := re.match(r'POINTER ([01])', l))]
injected = sum(1 for l in lines if l.startswith('INJECT '))
if injected != 5:
    print('FAIL cursor: expected 5 injected events, got %d' % injected)
    sys.exit(1)
if seen != [0, 1, 0, 1]:
    print('FAIL cursor: expected POINTER 0, 1, 0, 1 - got %s' % seen)
    sys.exit(1)
print('PASS cursor: finger hides (0), mouse shows (1), finger hides (0), mouse shows again (1) after an unknown touch device')
