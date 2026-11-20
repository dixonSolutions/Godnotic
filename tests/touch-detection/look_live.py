#!/usr/bin/env python3
"""Keyboard without mouse on a touchscreen, live: a finger drag and the arrow keys
both turn the view, FIRE and HOP stay, only the move stick hides; and it follows a
keyboard that appears, disappears and wakes again mid-match, without a restart."""
import os, pathlib, random, re, subprocess, sys, tempfile, time
root = pathlib.Path(__file__).resolve().parents[2]
engine = pathlib.Path(os.environ.get('ENGINE', root / 'engine/darkplaces/darkplaces-sdl'))
base, stock = os.environ.get('BASEDIR'), os.environ.get('STOCKBASE')
if not engine.exists() or not base or not stock:
    print('SKIP: set ENGINE, BASEDIR and STOCKBASE'); sys.exit(77)
fixtures = root / 'tests/input-presence/fixtures'
touch = (fixtures / 'phone-touch-only.txt').read_text()
keyboard = (fixtures / 'desktop-usb-keyboard-mouse-pad.txt').read_text().split('\n\n')[0]
with_kbd = touch + '\n\n' + keyboard + '\n'
# (stage, device list it runs under, move stick expected)
stages = [('touch', touch, True), ('kbd_appears', with_kbd, False), ('kbd_gone', touch, True),
          ('kbd_wakes', with_kbd, False)]
env = os.environ.copy(); env.update(SDL_VIDEODRIVER='offscreen', SDL_AUDIODRIVER='dummy')
env.pop('DISPLAY', None); env.pop('WAYLAND_DISPLAY', None)
CPUS = os.environ.get('TEST_CPUS', '12-15')
with tempfile.TemporaryDirectory(prefix='kbd-look-') as tmp:
    t = pathlib.Path(tmp); (t / 'server/data').mkdir(parents=True); (t / 'client/data').mkdir(parents=True)
    device = t / 'devices'; device.write_text(touch)
    port = str(random.randrange(27000, 28000))
    serverlog = (t / 'server.log').open('w')
    server = subprocess.Popen(['taskset', '-c', CPUS, str(engine), '-dedicated', '-xonotic', '-basedir', stock,
        '-userdir', str(t / 'server'), '+set', 'port', port, '+set', 'sv_public', '0', '+set', 'bot_number', '0',
        '+set', 'g_warmup', '0', '+map', 'stormkeep'], stdout=serverlog, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if 'Server spawned.' in (t / 'server.log').read_text(errors='replace'): break
            if server.poll() is not None: raise RuntimeError('dedicated server exited before spawn')
            time.sleep(.1)
        else: raise RuntimeError('dedicated server did not spawn')
        cfg = [f'connect 127.0.0.1:{port}', 'defer 26 "togglemenu 0; cmd join"']
        for n, (name, _, _) in enumerate(stages):
            b = 32 + n * 9
            d = lambda dt, c: cfg.append(f'defer {b + dt} "{c}"')
            d(0, f'echo SWITCH_{name}'); d(2, 'vid_touchscreen_rescan')
            d(3, f'echo STAGE_{name}; touch_hud_state rects; _touch_test_view')
            d(3.2, '_touch_test_finger 0 1 0.5 0.5')
            for k, x in enumerate((0.55, 0.62, 0.7, 0.78, 0.85)):
                d(3.4 + k * .2, f'_touch_test_finger 0 1 {x} 0.5')
            d(4.6, '_touch_test_view'); d(4.7, '_touch_test_finger 0 0')
            d(5.0, '+left'); d(6.0, '-left'); d(6.2, '_touch_test_view')
        cfg += [f'defer {32 + len(stages) * 9 + 1} "quit"']
        (t / 'client/data/test.cfg').write_text('\n'.join(cfg) + '\n')
        # A host libjpeg newer than the headers the engine was built with aborts it at the first
        # JPEG; hide it (PNG/TGA textures are enough for this test).
        nojpeg = [a for lib in sorted(pathlib.Path('/usr/lib/x86_64-linux-gnu').glob('libjpeg.so*')) if lib.is_file() or lib.is_symlink()
                  for a in ('--ro-bind', '/dev/null', str(lib))]
        cmd = ['bwrap', '--bind', '/', '/', '--dev-bind', '/dev', '/dev', *nojpeg, '--ro-bind', str(device), '/proc/bus/input/devices',
               'taskset', '-c', CPUS, str(engine), '-xonotic', '-basedir', base, '-userdir', str(t / 'client'),
               '+vid_fullscreen', '0', '+vid_width', '640', '+vid_height', '360', '+set', '_touch_assets_ready', '1',
               '+set', 'touch_join_dialog_auto', '0', '+set', 'vid_touchscreen_mode', '1', '+set', 'vid_touchscreen_live', '1',
               '+exec', 'test.cfg']
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', env=env)
        output = []; sections = {}; current = None
        for line in proc.stdout:
            output.append(line)
            for name, value, _ in stages:
                if line.strip() == 'SWITCH_' + name:
                    device.write_text(value); current = None
            if line.strip().startswith('STAGE_'):
                current = line.strip()[6:]; sections[current] = []
            elif current:
                sections[current].append(line)
        rc = proc.wait(timeout=180)
        failures = []
        for name, _, stick in stages:
            text = ''.join(sections.get(name, []))
            views = [(float(a), float(b)) for a, b in re.findall(r'TESTVIEW yaw=(-?[\d.]+) pitch=(-?[\d.]+)', text)]
            has = lambda pat: bool(re.search(pat, text))
            if 'TOUCHHUD mode=playing' not in text:
                failures.append(f'{name}: not in a playing HUD')
            if has(r'TOUCHRECT c stick ') != stick:
                failures.append(f'{name}: move stick shown={not stick}, wanted {stick}')
            if not (has(r'TOUCHRECT c FIRE ') and has(r'TOUCHRECT .* HOP ')):
                failures.append(f'{name}: FIRE or HOP missing')
            if len(views) != 3:
                failures.append(f'{name}: got {len(views)} view readings'); continue
            dyaw = lambda a, b: abs((b - a + 180) % 360 - 180)
            if dyaw(views[0][0], views[1][0]) < 1:
                failures.append(f'{name}: a finger drag did not turn the view {views[:2]}')
            if dyaw(views[1][0], views[2][0]) < 5:
                failures.append(f'{name}: +left (arrow key) did not turn the view {views[1:]}')
        if rc or failures:
            print('FAIL keyboard without mouse, live:', *failures, sep='\n'); print(''.join(output[-80:])); sys.exit(1)
        print('keyboard without mouse, live (drag + arrow turn, hot-plug): OK')
    finally:
        server.terminate()
        try: server.wait(timeout=5)
        except subprocess.TimeoutExpired: server.kill(); server.wait()
        serverlog.close()
