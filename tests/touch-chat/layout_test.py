#!/usr/bin/env python3
"""Exercise the engine's chat input placement with varying screen sizes."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'engine/darkplaces/touch_ui.c').read_text()
match = re.search(r'float TouchUI_ChatInputTop\(float height\)\s*\{[^}]*\}', source)
assert match, 'chat input layout function missing'
# Use representative measured header heights; the input must stay just below
# the header and in the top half regardless of the keyboard's height.
program = '''
#include <assert.h>
static float header;
float TouchUI_SheetHeaderHeight(float h) { (void)h; return header; }
''' + match.group() + '''
int main(void) {
    float heights[] = {320, 600, 1080};
    float headers[] = {40, 60, 64};
    for (int i = 0; i < 3; ++i) {
        header = headers[i];
        float top = TouchUI_ChatInputTop(heights[i]);
        assert(top >= header && top < heights[i] * 0.5f);
    }
    return 0;
}
'''
with tempfile.TemporaryDirectory() as tmp:
    cfile = Path(tmp) / 'layout.c'
    binary = Path(tmp) / 'layout'
    cfile.write_text(program)
    subprocess.run(['cc', '-std=c99', '-Wall', '-Werror', str(cfile), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print('PASS: chat input stays below header in the top half at three screen heights')
