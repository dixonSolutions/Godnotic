#!/usr/bin/env python3
"""The dead player's prompt names the button the touch layer draws: RESPAWN, not HOP."""
import re, sys
root = sys.argv[1]
c = root + "/engine/data/xonotic-data.pk3dir/qcsrc/client/"
defs = open(c + "touch_defs.qh").read()
inp = open(c + "touch_input.qc").read()
mode = open(c + "touch_mode.qc").read()
sb = open(c + "hud/panel/scoreboard.qc").read()
m = re.search(r'TOUCH_LABEL_RESPAWN\s*=\s*"([^"]+)"', defs)
assert m and m.group(1) == "RESPAWN", "TOUCH_LABEL_RESPAWN must be RESPAWN"
# the drawn button uses the constant, so prompt and button cannot drift
assert "half, TOUCH_LABEL_RESPAWN," in mode, "Touch_DrawRespawn must draw TOUCH_LABEL_RESPAWN"
# the prompt asks Touch_CommandLabel("+jump"), which answers RESPAWN while dead, before HOP
fn = inp[inp.index("string Touch_CommandLabel"):]
fn = fn[:fn.index("\n}\n")]
dead = fn.index("TOUCHMODE_DEAD")
assert fn.index("TOUCH_LABEL_RESPAWN") > dead
assert dead < fn.index("TOUCH_LABEL_JUMP"), "dead check must come before the HOP label"
assert 'Touch_CommandLabel("+jump")' in sb and "to respawn" in sb
print("ok   respawn prompt names RESPAWN")
