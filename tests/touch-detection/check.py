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
static int decide(int touch, int keyboard, int mouse, int used, int mode) {
    qbool has_touch, touch_only;
    presence = (vid_presence_t){touch, keyboard, mouse};
    (void)used;
    VID_DetectTouchHardware(&has_touch, &touch_only);
    return vid_dynamic_want(has_touch, touch_only, keyboard, mode, 1);
}
int main(void) {
    /* touchscreen, keyboard only: nothing else can aim, so the controls stay */
    assert(decide(1, 1, 0, 0, 1));
    /* a listed mouse and keyboard switch to desktop immediately */
    assert(!decide(1, 1, 1, 0, 1));
    /* a mouse that has moved is the desktop */
    assert(!decide(1, 1, 1, 1, 1));
    /* a mouse without a keyboard cannot aim and fire together: controls stay */
    assert(decide(1, 0, 1, 1, 1));
    /* no hardware to aim with at all */
    assert(decide(1, 0, 0, 0, 1));
    assert(!decide(0, 0, 0, 0, 1));
    /* hot-plug: the same keyboard with the used mouse unplugged */
    assert(decide(1, 1, 0, 1, 1));
    /* legacy mode 3 keeps its keyboard-only rule */
    assert(!decide(1, 1, 0, 0, 3));
    assert(decide(1, 0, 0, 0, 3));
    assert(!decide(0, 0, 0, 0, 3));
}
'''
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'test.c'
    src.write_text(code)
    subprocess.run(['cc', '-std=c99', '-Wall', '-Wextra', '-Werror', str(src), '-o', tmp + '/test'], check=True)
    subprocess.run([tmp + '/test'], check=True)

# Android: a mouse the activity lists but that never moved is not a mouse (a tablet's own
# pad beside a Bluetooth keyboard used to read as one and switched the controls off).
m = re.search(r'#if defined\(__ANDROID__\)\n\t\{\n(.*?)\n\t\}\n#elif defined\(DP_MOBILETOUCH\)', source, re.S)
assert m, 'android presence block'
acode = """#include <assert.h>
#include <stdbool.h>
typedef int qbool;
#define __ATOMIC_ACQUIRE_ __ATOMIC_ACQUIRE
typedef struct { qbool touch, keyboard, mouse; } vid_presence_t;
#define VID_ANDROID_TOUCH 1
#define VID_ANDROID_KEYBOARD 2
#define VID_ANDROID_MOUSE 4
#define VID_ANDROID_VALID 8
static int vid_android_state, vid_android_seen, vid_touch_finger_seen;
static qbool vid_android_mouse_used;
static void compute(vid_presence_t *p) {
    p->touch = p->keyboard = p->mouse = 0;
""" + m.group(1) + """
}
int main(void) {
    vid_presence_t p;
    vid_android_state = 8 | 1 | 2 | 4;           /* touch, keyboard, a listed mouse never moved */
    compute(&p); assert(p.keyboard && !p.mouse);
    vid_android_mouse_used = 1;                  /* it moved */
    compute(&p); assert(p.keyboard && p.mouse);
    vid_android_state = 8 | 1 | 2;               /* unplugged */
    compute(&p); assert(!p.mouse && !vid_android_mouse_used);
    vid_android_state = 8 | 1 | 2 | 4;           /* plugged again: unused until it moves */
    compute(&p); assert(!p.mouse);
    return 0;
}
"""
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / 'android.c'
    src.write_text(acode)
    subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', str(src), '-o', tmp + '/android'], check=True)
    subprocess.run([tmp + '/android'], check=True)
assert 'vid_android_mouse_used = true;' in function('VID_NoteMouseUse'), 'real motion must mark the Android mouse used'
print('touch hardware decisions: OK')
