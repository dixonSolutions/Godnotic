#!/usr/bin/env python3
"""Keep both touch chat composers free of preset and option controls."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
engine = (root / 'engine/darkplaces/touch_ui.c').read_text()
qc = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/client/touch_chat.qc').read_text()

sheet = engine[engine.index('int TouchUI_LayoutSheet('):engine.index('static void TouchUI_EmitKey', engine.index('int TouchUI_LayoutSheet('))]
chat = sheet[sheet.index('else /* chat */'):]
assert 'TOUCHUI_ACT_PALETTE' not in chat and 'TouchUI_ReloadPalette' not in chat
assert 'TouchUI_LayoutKeyboard' in chat
header = engine[engine.index('static int TouchUI_LayoutHeader('):engine.index('static int TouchUI_LayoutSampleLog(')]
assert 'kind != TOUCHUI_SHEET_PREVIEW && kind != TOUCHUI_SHEET_CHAT' in header
assert 'TOUCHUI_ACT_CLOSE' in header and 'K_ENTER' in header
assert 'TOUCH_CHAT_BTN_COUNT = 1' in qc and 'TOUCH_CHAT_BTN_CLOSE' in qc
assert 'TOUCH_CHAT_COLOURS' not in qc and 'TOUCH_CHAT_BTN_TEAM' not in qc and 'TOUCH_CHAT_BTN_KEYS' not in qc
assert 'Touch_Widget_Field(' in qc and 'Touch_Kb_Draw(' in qc
assert 'Touch_Chat_FieldCenter().y + Touch_Chat_FieldHalf().y' in qc
print('PASS: chat composers retain typing and exit controls without preset or option buttons')
