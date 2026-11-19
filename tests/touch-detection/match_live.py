#!/usr/bin/env python3
"""Exercise both device detection and engine HUD rectangles in one live match."""
import os, pathlib, random, re, subprocess, sys, tempfile, time
root = pathlib.Path(__file__).resolve().parents[2]
engine = pathlib.Path(os.environ.get('ENGINE', root / 'engine/darkplaces/darkplaces-sdl'))
base, stock = os.environ.get('BASEDIR'), os.environ.get('STOCKBASE')
if not engine.exists() or not base or not stock:
    print('SKIP: set ENGINE, BASEDIR and STOCKBASE'); sys.exit(77)
fixtures = root / 'tests/input-presence/fixtures'
touch = (fixtures / 'phone-touch-only.txt').read_text()
keyboard, mouse = (fixtures / 'desktop-usb-keyboard-mouse-pad.txt').read_text().split('\n\n')[:2]
variants = {'keyboard': touch + '\n\n' + keyboard + '\n', 'mouse': touch + '\n\n' + mouse + '\n',
            'both': touch + '\n\n' + keyboard + '\n\n' + mouse + '\n', 'touch2': touch,
            'mouse_acc': touch + '\n\n' + mouse + '\n'}
env = os.environ.copy(); env.update(SDL_VIDEODRIVER='offscreen', SDL_AUDIODRIVER='dummy')
env.pop('DISPLAY', None); env.pop('WAYLAND_DISPLAY', None)
with tempfile.TemporaryDirectory(prefix='spec-device-match-') as tmp:
    t = pathlib.Path(tmp); (t / 'server/data').mkdir(parents=True); (t / 'client/data').mkdir(parents=True)
    device = t / 'devices'; device.write_text(touch)
    port = str(random.randrange(27000, 28000))
    serverlog = (t / 'server.log').open('w')
    server = subprocess.Popen(['taskset', '-c', '8-11', str(engine), '-dedicated', '-xonotic', '-basedir', stock,
        '-userdir', str(t / 'server'), '+set', 'port', port, '+set', 'sv_public', '0', '+set', 'bot_number', '0',
        '+set', 'g_warmup', '0', '+map', 'stormkeep'], stdout=serverlog, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if 'Server spawned.' in (t / 'server.log').read_text(errors='replace'): break
            if server.poll() is not None: raise RuntimeError('dedicated server exited before spawn')
            time.sleep(.1)
        else: raise RuntimeError('dedicated server did not spawn')
        cfg = [f'connect 127.0.0.1:{port}', 'defer 26 "togglemenu 0; cmd join"',
               'defer 33 "echo SNAP_touch; touch_hud_state rects; cl_touch_aim_input"']
        for n, name in enumerate(('keyboard', 'mouse', 'both', 'touch2', 'mouse_acc')):
            sec = 35 + n * 7
            # the last stage: a mouse again, with auto-shoot and aim assist asked for in Accessibility
            extra = '; set cl_accessibility_desktop 1' if name == 'mouse_acc' else ''
            cfg += [f'defer {sec} "echo SWITCH_{name}{extra}"', f'defer {sec+2} "vid_touchscreen_rescan"',
                    f'defer {sec+4} "echo SNAP_{name}; touch_hud_state rects; cl_touch_aim_input"']
        cfg += ['defer 72 "quit"']
        (t / 'client/data/test.cfg').write_text('\n'.join(cfg) + '\n')
        cmd = ['bwrap', '--bind', '/', '/', '--dev-bind', '/dev', '/dev', '--ro-bind', str(device), '/proc/bus/input/devices',
               'taskset', '-c', '8-11', str(engine), '-xonotic', '-basedir', base, '-userdir', str(t / 'client'),
               '+vid_fullscreen', '0', '+vid_width', '640', '+vid_height', '360', '+set', '_touch_assets_ready', '1',
               '+set', 'touch_join_dialog_auto', '0', '+set', 'vid_touchscreen_mode', '1', '+set', 'vid_touchscreen_live', '1',
               '+exec', 'test.cfg']
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', env=env)
        output = []; sections = {}; current = None
        for line in proc.stdout:
            output.append(line)
            for name, value in variants.items():
                if line.strip() == 'SWITCH_' + name:
                    device.write_text(value); current = None
            if line.strip().startswith('SNAP_'):
                current = line.strip()[5:]; sections[current] = []
            elif current:
                sections[current].append(line)
        rc = proc.wait(timeout=120)
        expected = {'touch': (True, True, True), 'keyboard': (False, True, True),
                    'mouse': (True, False, True), 'both': (False, False, False), 'touch2': (True, True, True),
                    'mouse_acc': (True, False, True)}
        # auto-shoot and aim assist: touch and keyboard-only on, a mouse off unless Accessibility asks
        aim = {'touch': 1, 'keyboard': 1, 'mouse': 0, 'both': 0, 'touch2': 1, 'mouse_acc': 1}
        failures = []
        for name, want in expected.items():
            text = ''.join(sections.get(name, []))
            found = (bool(re.search(r'TOUCHRECT c stick ', text)), bool(re.search(r'TOUCHRECT c FIRE ', text)), bool(re.search(r'TOUCHRECT .* HOP ', text)))
            m = re.search(r'cl_touch_aim_input(?:\x1b\[[0-9;]*m)? is "(\d)"', text)
            if not m or int(m.group(1)) != aim[name]:
                failures.append(f'{name}: auto-shoot/aim assist {m and m.group(1)}, wanted {aim[name]}')
            if 'TOUCHHUD mode=playing' not in text or found != want:
                failures.append(f'{name}: rectangles {found}, wanted {want}; ' + ' | '.join(x.strip() for x in sections.get(name, []) if 'TOUCH' in x))
        if rc or failures:
            print('FAIL live HUD device transitions:', *failures, sep='\n'); print(''.join(output[-80:])); sys.exit(1)
        print('live HUD device transitions: OK', expected)
    finally:
        server.terminate()
        try: server.wait(timeout=5)
        except subprocess.TimeoutExpired: server.kill(); server.wait()
        serverlog.close()
