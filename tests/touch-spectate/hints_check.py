#!/usr/bin/env python3
"""Guard the touch spectator prompt against inaccessible key instructions."""
import pathlib, sys
root = pathlib.Path(sys.argv[1])
s = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/client/touch_spectate.qc').read_text()
observer = s.split('string Touch_Spec_ObserverHint', 1)[1].split('string Touch_Spec_TeamHint', 1)[0]
assert 'vid_keyboard_detected' in observer
assert 'choice == 2' in observer and 'choice == 1' in observer
assert 'Tap ^3>^1 to spectate' in observer
assert 'Tap ^3FREE^1 to observe' in observer
join = s.split('string Touch_Spec_JoinHint', 1)[1].split('string Touch_Spec_CommandLabel', 1)[0]
assert 'vid_keyboard_detected' in join and 'Tap ^3JOIN' in join
pressed = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/client/hud/panel/pressedkeys.qc').read_text()
assert 'vid_keyboard_detected' in pressed and 'vid_touchscreen_detected' in pressed
print('ok   spectator touch hint guards')
