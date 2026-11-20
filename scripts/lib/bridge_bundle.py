"""Bundle web/bridge-*.mjs into one classic script for the page.

The modules stay ES modules (node tests import them); the page loads
classic scripts with content-hashed names, where relative imports would
break. Each module's imports are dropped (everything shares one scope) and
its `export` keywords removed. Used by scripts/build-web.sh.
"""
import os
import re
import sys

MODULES = ('bridge-frame.mjs', 'bridge-channel.mjs', 'bridge-mqtt.mjs', 'bridge-net.mjs')
EXPOSE = ('GodnoticNet', 'parseAddress', 'parseMessage', 'findRelays', 'connectRelay',
          'NO_RELAY', 'DEFAULT_BROKERS', 'DEFAULT_PREFIX', 'DEFAULT_STUN')


def bundle(web):
    parts = ["// Built from web/bridge-*.mjs by scripts/lib/bridge_bundle.py.\n(() => {\n'use strict';\n"]
    for name in MODULES:
        src = open(os.path.join(web, name), encoding='utf-8').read()
        src = re.sub(r'^import [^;]*;\n', '', src, flags=re.M)
        src = re.sub(r'^export (?=(const|function|class|async function) )', '', src, flags=re.M)
        if re.search(r'^\s*(import|export)\b', src, flags=re.M):
            raise SystemExit(f'bridge_bundle: {name}: unsupported import/export form')
        parts.append(f'// ---- {name}\n{src}\n')
    parts.append('window.GodnoticBridge = {' + ', '.join(EXPOSE) + '};\n})();\n')
    return ''.join(parts)


if __name__ == '__main__':
    sys.stdout.write(bundle(sys.argv[1]))
