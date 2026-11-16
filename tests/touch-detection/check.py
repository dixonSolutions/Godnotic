#!/usr/bin/env python3
"""Exercise the engine's hardware and dynamic-mode decisions together."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'engine/darkplaces/vid_touchdetect.c').read_text()

def function(name):
    match = re.search(r'\b' + name + r'\([^)]*\)\s*\{', source)
    assert match, name
    start = match.start()
    depth = 1
    for i in range(match.end(), len(source)):
        depth += (source[i] == '{') - (source[i] == '}')
        if not depth:
            return source[start:i + 1]
    raise AssertionError(name)

code = '''#include <assert.h>
typedef int qbool;
typedef struct { qbool touch, keyboard, mouse; } vid_presence_t;
static vid_presence_t presence;
static const vid_presence_t *vid_presence_get(void) { return &presence; }
void ''' + function('VID_DetectTouchHardware') + '\nstatic int ' + function('vid_dynamic_want') + '''
static int decide(int touch, int keyboard, int mouse, int mode) {
    qbool has_touch, touch_only;
    presence = (vid_presence_t){touch, keyboard, mouse};
    VID_DetectTouchHardware(&has_touch, &touch_only);
    return vid_dynamic_want(has_touch, touch_only, keyboard, mode, 1);
}
int main(void) {
    assert(decide(1, 1, 0, 1));
    assert(!decide(1, 1, 1, 1));
    assert(decide(1, 0, 0, 1));
    assert(!decide(0, 0, 0, 1));
    assert(!decide(1, 1, 0, 3));
    assert(decide(1, 0, 0, 3));
    assert(!decide(0, 0, 0, 3));
}
'''
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'test.c'
    src.write_text(code)
    subprocess.run(['cc', '-std=c99', '-Wall', '-Wextra', '-Werror', str(src), '-o', tmp + '/test'], check=True)
    subprocess.run([tmp + '/test'], check=True)
print('touch hardware decisions: OK')
