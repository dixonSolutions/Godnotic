#!/usr/bin/env python3
"""Both pause descriptions expose an immediate disconnect action."""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
pause = json.loads((root / 'android/app/src/main/assets/fe/pause.json').read_text())

def walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)

actions = [n for n in walk(pause) if n.get('id') == 'disconnect']
assert actions and all('confirm' not in a for a in actions)
uib = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_screens.qc').read_text()
section = uib[uib.index('UIB_Open("disconnect", "button")'):]
section = section[:section.index('UIB_Close();')]
assert 'UIB_AS("disconnect"' in section and 'UIB_AC(' not in section
assert 'case "disconnect": localcmd("\\ntouch_action leave\\n"); return;' in uib
frontend = (root / 'engine/darkplaces/touch_frontend.c').read_text()
assert 'else if (!strcmp(what, "leave"))' in frontend
print('leave: native and UIB pause actions have no confirmation')
