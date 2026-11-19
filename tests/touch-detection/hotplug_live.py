#!/usr/bin/env python3
"""Re-scan fake procfs device lists in one offscreen engine process."""
import os, pathlib, subprocess, tempfile, sys
root = pathlib.Path(__file__).resolve().parents[2]
engine = pathlib.Path(os.environ.get('ENGINE', root / 'engine/darkplaces/darkplaces-sdl'))
base = os.environ.get('BASEDIR')
if not engine.exists() or not base:
    print('SKIP: set ENGINE and BASEDIR'); sys.exit(77)
fixtures = root / 'tests/input-presence/fixtures'
touch = (fixtures / 'phone-touch-only.txt').read_text()
blocks = (fixtures / 'desktop-usb-keyboard-mouse-pad.txt').read_text().split('\n\n')
keyboard, mouse = blocks[:2]
variants = {'keyboard': touch + '\n\n' + keyboard + '\n', 'mouse': touch + '\n\n' + mouse + '\n',
            'both': touch + '\n\n' + keyboard + '\n\n' + mouse + '\n', 'touch': touch}
with tempfile.TemporaryDirectory(prefix='spec-devices-') as td:
    t = pathlib.Path(td); (t / 'home/data').mkdir(parents=True)
    device = t / 'devices'; device.write_text(touch)
    cfg = ['vid_touchscreen_rescan']
    for n, name in enumerate(('keyboard', 'mouse', 'both', 'touch')):
        sec = 4 + n * 5
        cfg += [f'defer {sec} "echo SWITCH_{name}"', f'defer {sec+2} "vid_touchscreen_rescan"']
    cfg += ['defer 26 "quit"']
    (t / 'home/data/test.cfg').write_text('\n'.join(cfg) + '\n')
    env = os.environ.copy(); env.update(SDL_VIDEODRIVER='offscreen', SDL_AUDIODRIVER='dummy')
    env.pop('DISPLAY', None); env.pop('WAYLAND_DISPLAY', None)
    cmd = ['bwrap', '--bind', '/', '/', '--dev-bind', '/dev', '/dev', '--ro-bind', str(device), '/proc/bus/input/devices',
           'taskset', '-c', '8-11', str(engine), '-xonotic', '-basedir', base, '-userdir', str(t / 'home'),
           '+set', 'vid_touchscreen_mode', '1', '+set', 'vid_touchscreen_live', '1', '+exec', 'test.cfg']
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', env=env)
    output = []
    for line in proc.stdout:
        output.append(line)
        for name, value in variants.items():
            if line.strip() == 'SWITCH_' + name:
                device.write_text(value)  # retain the inode mounted by bwrap
    code = proc.wait(timeout=90)
    lines = [s for s in output if s.startswith('vid_touchscreen_mode ')]
    expected = [(1,0,0), (1,1,0), (1,0,1), (0,1,1), (1,0,0)]
    import re
    got = []
    for line in lines:
        m = re.search(r'vid_touchscreen (\d+), detected \d+, touch-only \d+, keyboard (\d+), mouse (\d+)', line)
        if m: got.append(tuple(map(int, m.groups())))
    if code or got[-5:] != expected:
        print('FAIL fake-device live transitions:', got, 'exit', code)
        print(''.join(output[-80:])); sys.exit(1)
    print('fake-device live transitions: OK', got[-5:])
