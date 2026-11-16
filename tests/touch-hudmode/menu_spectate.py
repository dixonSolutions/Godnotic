#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The MENU sheet can follow, switch target and go free with the spectator bar
off (touch_specbar_visible 0). Both in-match menus build their spectator rows
from the engine's action list (_touch_act_follow / _next / _prev / _free) and
run them through touch_action, so the bar is a shortcut, never the only way.

Checks the touch game menu (dialog_touch_gamemenu.qc) and the UIB pause sheet
(touch_uib_screens.qc). See docs/CONTROLS.md, "Menu actions"."""
import sys

qc = open(sys.argv[1], encoding="utf-8").read()
uib = open(sys.argv[2], encoding="utf-8").read()
ids = ("follow", "next", "prev", "free")
errors = []

for action in ids:
    if f"_touch_act_{action}" not in qc:
        errors.append(f"touch game menu: no _touch_act_{action} button")
    if f"touch_action {action}" not in qc:
        errors.append(f"touch game menu: no touch_action {action} command")
    if f'"_touch_act_{action}"' not in uib:
        errors.append(f"UIB pause: no _touch_act_{action} button")
    if f'case "{action}"' not in uib:
        errors.append(f"UIB pause: no action handler for {action}")

for e in errors:
    print("menu spectator:", e)
if errors:
    sys.exit(1)
print("menu spectator: follow / next / prev / free on both MENU sheets, bar independent")
