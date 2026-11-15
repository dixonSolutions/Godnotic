#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# X11 helper for scripts/test-gtk-live.sh (libX11 through ctypes, no other modules).
# Runs against $DISPLAY, which the test sets to its private Xvfb.
#
#   xwin.py watch <out> <seconds>   every 50 ms: "<t> <n> <names>" with the mapped
#                                   top-level windows (children of the root that are
#                                   viewable) and their size
#   xwin.py focus none              take the keyboard focus away from every window
#                                   (another app was clicked)
#   xwin.py focus <title>           give it to the top-level window with that title
#                                   (a window manager does this on a click or Alt+Tab)
#   xwin.py close-visible           ask every mapped top-level window to close
#                                   (WM_DELETE_WINDOW: its title bar's close button)
import ctypes
import ctypes.util
import sys
import time

x = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
Window = ctypes.c_ulong


class Attrs(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int), ("height", ctypes.c_int),
                ("border_width", ctypes.c_int), ("depth", ctypes.c_int), ("visual", ctypes.c_void_p),
                ("root", Window), ("c_class", ctypes.c_int), ("bit_gravity", ctypes.c_int),
                ("win_gravity", ctypes.c_int), ("backing_store", ctypes.c_int),
                ("backing_planes", ctypes.c_ulong), ("backing_pixel", ctypes.c_ulong),
                ("save_under", ctypes.c_int), ("colormaps", ctypes.c_ulong), ("map_installed", ctypes.c_int),
                ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long), ("your_event_mask", ctypes.c_long),
                ("do_not_propagate_mask", ctypes.c_long), ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p)]


x.XOpenDisplay.restype = ctypes.c_void_p
x.XOpenDisplay.argtypes = [ctypes.c_char_p]
x.XDefaultRootWindow.restype = Window
x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x.XQueryTree.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(Window), ctypes.POINTER(Window),
                         ctypes.POINTER(ctypes.POINTER(Window)), ctypes.POINTER(ctypes.c_uint)]
x.XGetWindowAttributes.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(Attrs)]
x.XFetchName.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(ctypes.c_char_p)]
x.XSetInputFocus.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_ulong]
x.XFree.argtypes = [ctypes.c_void_p]
x.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
x.XSetErrorHandler.restype = ctypes.c_void_p
HANDLER = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda d, e: 0)
x.XSetErrorHandler(HANDLER)

d = x.XOpenDisplay(None)
if not d:
    sys.exit("xwin: no display")
root = x.XDefaultRootWindow(d)


def toplevels():
    r, p, kids, n = Window(), Window(), ctypes.POINTER(Window)(), ctypes.c_uint()
    out = []
    if not x.XQueryTree(d, root, ctypes.byref(r), ctypes.byref(p), ctypes.byref(kids), ctypes.byref(n)):
        return out
    for i in range(n.value):
        w = kids[i]
        a = Attrs()
        if not x.XGetWindowAttributes(d, w, ctypes.byref(a)) or a.map_state != 2:  # IsViewable
            continue
        name = ctypes.c_char_p()
        x.XFetchName(d, w, ctypes.byref(name))
        title = name.value.decode(errors="replace") if name.value else "-"
        out.append((w, title.replace(" ", "_"), a.width, a.height))
    if n.value:
        x.XFree(kids)
    return out


if sys.argv[1] == "watch":
    out, secs = sys.argv[2], float(sys.argv[3])
    t0 = time.monotonic()
    with open(out, "w") as f:
        f.write("# t0 %.3f (monotonic)\n" % t0)
        while time.monotonic() - t0 < secs:
            tl = toplevels()
            f.write("%.2f %d %s\n" % (time.monotonic() - t0, len(tl), " ".join("%s:%dx%d" % (t, w, h) for _, t, w, h in tl)))
            f.flush()
            time.sleep(0.05)
elif sys.argv[1] == "close-visible":
    class ClientMessage(ctypes.Structure):
        _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                    ("display", ctypes.c_void_p), ("window", Window), ("message_type", ctypes.c_ulong),
                    ("format", ctypes.c_int), ("data", ctypes.c_long * 5)]

    class XEvent(ctypes.Union):
        _fields_ = [("xclient", ClientMessage), ("pad", ctypes.c_long * 24)]

    x.XInternAtom.restype = ctypes.c_ulong
    x.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    x.XSendEvent.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_long, ctypes.POINTER(XEvent)]
    protocols = x.XInternAtom(d, b"WM_PROTOCOLS", 0)
    delete = x.XInternAtom(d, b"WM_DELETE_WINDOW", 0)
    for w, t, _, _ in toplevels():
        ev = XEvent()
        ev.xclient.type = 33  # ClientMessage
        ev.xclient.window = w
        ev.xclient.message_type = protocols
        ev.xclient.format = 32
        ev.xclient.data[0] = delete
        ev.xclient.data[1] = 0  # CurrentTime
        x.XSendEvent(d, w, 0, 0, ctypes.byref(ev))
        print("close", hex(w), t)
    x.XSync(d, 0)
elif sys.argv[1] == "focus":
    target = 0  # None
    if sys.argv[2] != "none":
        for w, t, _, _ in toplevels():
            if t == sys.argv[2]:
                target = w
    x.XSetInputFocus(d, target, 2 if target else 0, 0)  # RevertToParent / RevertToNone, CurrentTime
    x.XSync(d, 0)
    print("focus", hex(target))
