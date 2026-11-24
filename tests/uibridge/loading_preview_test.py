#!/usr/bin/env python3
"""Live engine/bridge test of the map image exposed while joining a local server.

Requires UIB_BASEDIR (game data), UIB_MENU_DAT (menu compiled from this checkout),
UIB_ENGINE (SDL build), and UIB_SERVER (dedicated build). UIB_LIBDIR is optional.
"""
import os
import shutil
import socket
import subprocess
import tempfile
import time

import engine_test as t

server = os.environ['UIB_SERVER']
menu = os.environ['UIB_MENU_DAT']
with tempfile.TemporaryDirectory(prefix='uib-loading-') as work:
    serverdir = os.path.join(work, 'server')
    clientdir = os.path.join(work, 'client')
    os.makedirs(os.path.join(serverdir, 'data'))
    os.makedirs(os.path.join(clientdir, 'data'))
    shutil.copy(menu, os.path.join(clientdir, 'data', 'menu.dat'))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as port_socket:
        port_socket.bind(('127.0.0.1', 0))
        port = port_socket.getsockname()[1]
    env = dict(os.environ, SDL_VIDEODRIVER='offscreen', SDL_AUDIODRIVER='dummy')
    if t.LIBDIR:
        env['LD_LIBRARY_PATH'] = t.LIBDIR
    with open(os.path.join(work, 'server.log'), 'w+') as log:
        srv = subprocess.Popen([server, '-dedicated', '-xonotic', '-basedir', t.BASEDIR,
            '-userdir', serverdir, '+set', 'port', str(port), '+set', 'sv_public', '0',
            '+set', 'bot_number', '0', '+map', 'afterslime'], env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(300):
                log.seek(0)
                if 'Server spawned' in log.read():
                    break
                if srv.poll() is not None:
                    raise RuntimeError('local server exited before starting')
                time.sleep(0.1)
            else:
                raise TimeoutError('local server did not start')
            sock = os.path.join(work, 'uib.sock')
            p, plog = t.spawn(['-uibridge', 'listen:' + sock, '+defer', '8',
                f'connect 127.0.0.1:{port}'], clientdir, 'set touch_profile_done 1\nset _cl_name Test\n')
            try:
                c = t.Conn(t.connect(sock))
                t.hello(c)
                end = time.monotonic() + 25
                while time.monotonic() < end:
                    c.recv(0.5)
                    node = c.nodes.get('loading/map')
                    if node:
                        context = c.nodes.get('app', {}).get('props', {}).get('context')
                        assert context == 'loading', f'loading/map published with app.context={context!r}'
                        ref = node['props'].get('ref')
                        assert ref in ('pk3:maps/afterslime', 'pk3:maps/afterslime.jpg', 'pk3:maps/afterslime.png'), ref
                        image = c.request({'t': 'image', 'ref': ref, 'w': 1024, 'h': 1024}, limit=15)
                        assert image and os.path.isfile(image.get('path', '')), image
                        context = c.nodes.get('app', {}).get('props', {}).get('context')
                        assert context == 'loading', f'preview arrived only after app.context={context!r}'
                        assert t.fails == 0, f'{t.fails} invalid bridge messages'
                        print('loading preview: map image arrived while app.context remained loading')
                        break
                else:
                    raise AssertionError('no map image in loading screen')
            finally:
                p.terminate()
                p.wait(timeout=10)
                plog.close()
        finally:
            srv.terminate()
            srv.wait(timeout=10)

# A local game has no remote connect handshake: its map and levelshot must be
# published from SV_SpawnServer while the GTK loading page still owns the screen.
with tempfile.TemporaryDirectory(prefix='uib-local-loading-') as work:
    userdir = os.path.join(work, 'user')
    os.makedirs(os.path.join(userdir, 'data'))
    shutil.copy(menu, os.path.join(userdir, 'data', 'menu.dat'))
    sock = os.path.join(work, 'uib.sock')
    p, plog = t.spawn(['-uibridge', 'listen:' + sock], userdir,
        'set touch_lan_map afterslime\nset touch_lan_gametype dm\nset touch_lan_bots 0\nset touch_profile_done 1\n')
    try:
        c = t.Conn(t.connect(sock))
        t.hello(c)
        c.send({'t': 'ev', 'ev': 'action', 'node': 'home/tiles/sp', 'action': 'sp'})
        assert t.wait_for(c, lambda: 'sp' in c.nodes, 10), 'Singleplayer did not open'
        c.send({'t': 'ev', 'ev': 'action', 'node': 'sp', 'action': 'play'})
        end = time.monotonic() + 25
        while time.monotonic() < end:
            c.recv(0.5)
            node = c.nodes.get('loading/map')
            if node:
                context = c.nodes.get('app', {}).get('props', {}).get('context')
                assert context == 'loading', f'local preview published with app.context={context!r}'
                ref = node['props'].get('ref')
                assert ref == 'pk3:maps/afterslime.jpg', ref
                image = c.request({'t': 'image', 'ref': ref, 'w': 1024, 'h': 1024}, limit=15)
                assert image and os.path.isfile(image.get('path', '')), image
                context = c.nodes.get('app', {}).get('props', {}).get('context')
                assert context == 'loading', f'local image arrived after app.context={context!r}'
                break
        else:
            raise AssertionError('no map image in local loading screen')
        assert t.wait_for(c, lambda: c.nodes.get('app', {}).get('props', {}).get('context') == 'game', 25), \
            'local map did not reach the signed-on game'
        assert t.fails == 0, f'{t.fails} invalid bridge messages'
        print('loading preview: local image arrived during loading and the game signed on')
    finally:
        p.terminate()
        p.wait(timeout=10)
        plog.close()
