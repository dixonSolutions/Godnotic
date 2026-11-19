#!/usr/bin/env python3
"""The input-device HUD policy must be the same in both touch HUDs (engine touch_hud.c, bundled CSQC touch_*.qc):
no keyboard -> movement stick; no mouse -> fire / alt-fire and the look area. Source guards: the live
checks (match_live.py) only see the engine HUD."""
import pathlib, re, sys
root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2])
qc = root / 'engine/data/xonotic-data.pk3dir/qcsrc/client'
c = (root / 'engine/darkplaces/touch_hud.c').read_text()
inp = (qc / 'touch_input.qc').read_text()
draw = (qc / 'touch_draw.qc').read_text()
spec = (qc / 'touch_spectate.qc').read_text()

def body(text, name):
    i = text.index(name)
    return text[i:i + 6000]

fails = []
def need(cond, msg):
    if not cond: fails.append(msg)

for label, text, assign, kb, mouse in (
        ('engine', c, 'static int assign_role(', 'cv("vid_keyboard_detected", 0)', 'cv("vid_mouse_confirmed", 0)'),
        ('qc', inp, 'int Touch_AssignRole(', 'cvar("vid_keyboard_detected")', 'cvar("vid_mouse_confirmed")')):
    b = body(text, assign)
    need(re.search(r'!' + re.escape(kb) + r' && \((in_move_zone|Touch_InMoveZone)', b), f'{label}: the move stick is not gated on a keyboard')
    need(re.search(r'!' + re.escape(mouse) + r' && .*(FIRE\b|fire_visible)', b), f'{label}: the FIRE button is not gated on a mouse')
    need(re.search(r'!' + re.escape(mouse) + r' && .*(fire2|Fire2)', b), f'{label}: the ALT button is not gated on a mouse')
    need(re.search(re.escape(mouse) + r' \|\| .*(role_owned|Touch_RoleOwned)\((ROLE|TOUCH_ROLE)_LOOK\)', b), f'{label}: the look catch-all is not dropped with a mouse')
    need(re.search(r'!' + re.escape(mouse) + r' && (in_look_zone|Touch_InLookZone)', b), f'{label}: the look zone is not gated on a mouse')

need('!cvar("vid_keyboard_detected") && autocvar_touch_move_visible' in draw, 'qc draw: stick drawn with a keyboard')
need('!cvar("vid_mouse_confirmed") && autocvar_touch_fire_visible' in draw, 'qc draw: FIRE drawn with a mouse')
need('!cvar("vid_keyboard_detected") && (Touch_InMoveZone' in spec and 'cvar("vid_mouse_confirmed") || Touch_RoleOwned' in spec, 'qc spectator roles ignore devices')
need('vid_keyboard_detected' in body(c, 'static void draw_spec_hud(') , 'engine spectator HUD stick ignores keyboard')
need('Touch_ReleaseAll();' in body(inp, 'void Touch_Frame(') and 'TouchHUD_ReleaseAll();' in body(c, 'void TouchHUD_Frame('), 'a device change does not release held fingers')

if fails:
    print('FAIL input-device HUD policy:', *fails, sep='\n  '); sys.exit(1)
print('ok   input-device HUD policy is the same in the engine HUD and the CSQC HUD')
