#!/usr/bin/env python3
"""Regression checks for pointer visibility and coordinate routing."""
from pathlib import Path
import re, subprocess, tempfile

root = Path(__file__).resolve().parents[2]
qc = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_render.qc').read_text()
sheet = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_menu_sheet.qc').read_text()
hud = (root / 'engine/darkplaces/touch_hud.c').read_text()
sdl = (root / 'engine/darkplaces/vid_sdl.c').read_text()

def body(source, name):
    match = re.search(r'\b' + name + r'\([^)]*\)\s*\{', source)
    assert match, name
    start = match.end(); depth = 1
    for i in range(start, len(source)):
        depth += (source[i] == '{') - (source[i] == '}')
        if not depth: return source[start:i]
    raise AssertionError(name)

# Compile and execute the actual QC helper (its simple scalar syntax is C too).
shown = body(sdl, 'VID_TouchscreenCursorShown')
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'test.c'
    src.write_text('#include <assert.h>\nint shown(float pointer_mouse) {' + shown + '}\n'
                   'int main(void) { assert(shown(1)); assert(!shown(0)); assert(!shown(0)); }\n')
    subprocess.run(['cc', '-std=c99', '-Werror', str(src), '-o', tmp + '/test'], check=True)
    subprocess.run([tmp + '/test'], check=True)  # mouse, finger, neither

draw = body(qc, 'TouchUIB_Draw')
down = body(qc, 'TouchUIB_KeyDown')
up = body(qc, 'TouchUIB_KeyUp')
assert 'VID_TouchscreenCursorShown(vid_pointer_mouse.integer)' in body(sdl, 'VID_TouchscreenCursor')
assert 'RB_PointerShown(cvar("vid_pointer_mouse"))' in draw
assert draw.count('RB_RRect(RB_PointerPos()') == 2
assert 'RB_Press(RB_PointerPos())' in down and 'RB_Release(RB_PointerPos())' in up
assert 'return rb_mouse;' in body(qc, 'RB_PointerPos')
assert '!cvar("vid_pointer_mouse") && cvar("menu_mouse_absolute")' in down
assert 'main.mouseMove(main, pos)' in body(sheet, 'TouchSheet_press')
mouse = body(hud, 'TouchHUD_OverlayMouse')
assert 'loading_finger(-1, true, nx, ny)' in mouse and 'loading_finger(-1, false, nx, ny)' in mouse
assert 'endmenu_finger(0, -1, nx, ny)' in mouse and 'endmenu_finger(2, -1, nx, ny)' in mouse
assert 'TouchHUD_OverlayMouse(' in sdl and 'synthetic' in mouse
assert 'vid_touchscreen.integer && !vid_pointer_mouse.integer' in body(sdl, 'VID_UpdateMouseGrab')
print('pointer visibility, shared coordinates and finger/mouse routing: OK')
