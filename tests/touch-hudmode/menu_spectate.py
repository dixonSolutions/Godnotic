#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Switching the spectated player (follow / next / prev / free) lives on the
spectator bar, which always shows on touch while observing; the MENU sheets no
longer duplicate it (user request, 2026-11-17). The UIB pause sheet must still
handle every action so a bound key or the bar can route through touch_action.

Checks the UIB pause sheet (touch_uib_screens.qc); the game menu argument is
kept for the caller's signature. See docs/CONTROLS.md, "Menu actions"."""
import sys

uib = open(sys.argv[2], encoding="utf-8").read()
ids = ("follow", "next", "prev", "free")
errors = []

for action in ids:
    if f'case "{action}"' not in uib:
        errors.append(f"UIB pause: no action handler for {action}")

for e in errors:
    print("menu spectator:", e)
if errors:
    sys.exit(1)
print("menu spectator: follow / next / prev / free handled; switching lives on the bar")
