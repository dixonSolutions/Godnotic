#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Settings > Updates in the Flatpak, in the real engine's QuakeC (docs/UI_BRIDGE.md).

    UIB_BASEDIR=<basedir> [UIB_ENGINE=build/bin/xonotic] [UIB_LIBDIR=...] \
        tests/uibridge/flatpak_updates_test.py [fixtures out dir]

The Linux app (loader/crates/gtk/src/updates.rs) writes update-status.txt with
via=flatpak and portal=yes; this writes each state it can write and checks what
the screen shows, then that Check now, Update now and Restart to finish leave
the request tokens the app serves. Without a portal (portal=none) the screen
keeps the package-manager text and says why. With a directory argument each
state is also saved as a fixture there (for loader/scripts/shot.sh).
"""
import importlib.util, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("uibfx", os.path.join(ROOT, "scripts", "uib-fixtures.py"))
fx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fx)
out_dir = sys.argv[1] if len(sys.argv) > 1 else ""
fail = 0


def check(ok, what):
    global fail
    print(("ok   " if ok else "FAIL ") + what)
    fail |= not ok


def status(state, installed, latest, message, *extra):
    return "\n".join(["1", state, installed, latest, "1" if state in ("available", "downloading", "ready") else "0",
                      extra[0] if state == "downloading" else "0", message, "off", "via=flatpak"]
                     + [e for e in extra if "=" in e] + [""])


STATES = {
    "uptodate": status("uptodate", "1.2.206", "1.2.206", "Up to date", "portal=yes", "checked_min=0", "checked_result=uptodate"),
    "available": status("available", "1.2.205", "1.2.206", "Version 1.2.206 is available", "portal=yes", "checked_min=3",
                        "checked_result=available", "commit=03c3d5707b0f", "remote_commit=6909a2fc6c97"),
    "downloading": status("downloading", "1.2.205", "1.2.206", "Updating: 30%", "30", "portal=yes", "step=1", "steps=2",
                          "checked_min=3", "checked_result=available"),
    "ready": status("ready", "1.2.205", "1.2.206", "Restart Godnotic to finish the update", "portal=yes", "checked_min=3",
                    "checked_result=available"),
    "error": status("error", "1.2.205", "1.2.206", "Self update not supported, new version requires new permissions",
                    "portal=yes", "checked_min=4", "checked_result=available", "commit=03c3d5707b0f", "remote_commit=6909a2fc6c97"),
    "noportal": status("managed", "1.2.206", "", "Updating from inside Godnotic needs the Flatpak portal (Flatpak 1.6 or newer).",
                       "portal=none"),
}


class Args:
    engine = os.environ.get("UIB_ENGINE", os.path.join(ROOT, "build", "bin", "xonotic"))
    basedir = os.environ["UIB_BASEDIR"]
    libdir = os.environ.get("UIB_LIBDIR", "")
    timeout = 180


def pane(e):
    """The Updates pane's children: {key: props}."""
    kids = {}

    def walk(i):
        n = e.nodes.get(i)
        if not n:
            return
        if i != "settings/split/updates":
            kids[i[len("settings/split/updates/"):]] = n.get("props", {})
        for c in n.get("children", []):
            walk(c)
    walk("settings/split/updates")
    return kids


def request_file(e):
    return os.path.join(e.ud, "data", "data", "touch", "update-request.txt")


def take_request(e):
    p = request_file(e)
    try:
        t = open(p).read().strip()
    except FileNotFoundError:
        return None
    os.remove(p)
    return t


e = fx.Engine(Args(), precfg=fx.NO_NETWORK)
try:
    e.hello()
    e.settle()
    e.ev(ev="action", node="home/nav/settings", action="settings")
    seen = {}
    for name, text in STATES.items():
        e.andysu_status(text, "update-status.txt")
        e.ev(ev="value", node="settings/split", value="audio")
        e.ev(ev="value", node="settings/split", value="updates")
        seen[name] = pane(e)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
            f = fx.fixture(e, f"settings-updates-flatpak-{name}", f"Settings > Updates in the Flatpak: {name}.")
            with open(os.path.join(out_dir, f"settings-updates-flatpak-{name}.json"), "w") as fh:
                json.dump(f, fh, indent=1)
        if name == "available":
            e.ev(ev="action", node="settings/split/updates/available/install", action="updates.install")
            e.settle()
            check(take_request(e) == "install", "Update now asks the app to install")
            e.ev(ev="action", node="settings/split/updates/check", action="updates.check")
            e.settle()
            check(take_request(e) == "check", "Check now asks the app to check (not the changelog)")
        if name == "ready":
            e.ev(ev="action", node="settings/split/updates/ready/restart", action="updates.restart")
            e.settle()
            check(take_request(e) == "restart", "Restart to finish asks the app to restart")
        if name == "noportal":
            e.ev(ev="action", node="settings/split/updates/check", action="updates.check")
            e.settle()
            check(take_request(e) is None, "without a portal Check now asks the changelog, not the app")
finally:
    e.close()

body = lambda kids, k: (kids.get(k) or {}).get("body", "")
s = seen["uptodate"]
check(body(s, "version") == "Version 1.2.206", "up to date: the installed version")
check(body(s, "newest") == "Newest version 1.2.206", "up to date: the remote's newest")
check("available" not in s and "ready" not in s and "download" not in s, "up to date: nothing to install")
check(body(s, "checked") == "Last checked just now: up to date", "up to date: when and how the last check went")
check(s.get("check", {}).get("enabled", True) is not False, "up to date: Check now is on")

s = seen["available"]
check(body(s, "version") == "Version 1.2.205" and body(s, "newest") == "Newest version 1.2.206", "available: installed and newest")
check(s.get("available", {}).get("title") == "Version 1.2.206 is out", "available: says which version is out")
check((s.get("available/install") or {}).get("label") == "Update now", "available: Update now")
check(body(s, "checked") == "Last checked 3 min ago: an update is available", "available: the last check")

s = seen["downloading"]
d = s.get("download") or {}
check(d.get("label") == "Downloading version 1.2.206" and abs(d.get("value", 0) - 0.3) < 1e-6, "updating: a progress bar at 30%")
check(body(s, "step") == "Step 1 of 2", "updating: which step")
check(s.get("check", {}).get("enabled") is False, "updating: Check now is off")
check("available" not in s, "updating: no second Update now")

s = seen["ready"]
check(s.get("ready", {}).get("title") == "Version 1.2.206 is installed", "installed: says so")
check((s.get("ready/restart") or {}).get("label") == "Restart to finish", "installed: Restart to finish")
check(s.get("check", {}).get("enabled") is False, "installed: Check now is off until the restart")

s = seen["error"]
check(body(s, "error") == "Self update not supported, new version requires new permissions", "error: the portal's words")
check((s.get("install") or {}).get("label") == "Try again" and s["install"].get("action", {}).get("label") == "Try again",
      "error: Try again while the remote is newer")
check(body(s, "elsewhere").startswith("GNOME Software, Discover or flatpak update"), "error: where else to update")

s = seen["noportal"]
check(body(s, "how").startswith("Godnotic updates through its Flatpak remote: GNOME Software, Discover or flatpak update"),
      "no portal: the package-manager text stays")
check(body(s, "portal") == "Checking and updating from inside Godnotic needs Flatpak 1.6 or newer.", "no portal: says why")
check("available" not in s and "ready" not in s, "no portal: no Update now")

print("flatpak updates:", "FAILED" if fail else "all passed")
sys.exit(1 if fail else 0)
