#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A stand-in for org.freedesktop.portal.Flatpak on a private bus, for
scripts/test-flatpak-updates.sh (the Rust client has its own in-process stub in
loader/crates/core/tests/flatpak_portal.rs).

    fake_portal.py serve ADDRESS LOG     own the name, answer `version` (8), make update
                                         monitors; Update sends Progress the way
                                         flatpak-portal does (two operations, then done);
                                         Spawn logs its argv, flags and environment
    fake_portal.py summary REF=COMMIT... an OSTree summary listing those refs, to stdout

Every call is logged to LOG as one JSON line.
"""
import json, sys, warnings
warnings.simplefilter("ignore", DeprecationWarning)
from gi.repository import Gio, GLib

BUS_NAME = "org.freedesktop.portal.Flatpak"
PATH = "/org/freedesktop/portal/Flatpak"
IFACE = "org.freedesktop.portal.Flatpak"
MONITOR = "org.freedesktop.portal.Flatpak.UpdateMonitor"

PORTAL_XML = """<node><interface name="org.freedesktop.portal.Flatpak">
  <property name="version" type="u" access="read"/><property name="supports" type="u" access="read"/>
  <method name="CreateUpdateMonitor"><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="Spawn"><arg type="ay" direction="in"/><arg type="aay" direction="in"/><arg type="a{uh}" direction="in"/>
    <arg type="a{ss}" direction="in"/><arg type="u" direction="in"/><arg type="a{sv}" direction="in"/><arg type="u" direction="out"/></method>
</interface></node>"""
MONITOR_XML = """<node><interface name="org.freedesktop.portal.Flatpak.UpdateMonitor">
  <method name="Close"/>
  <method name="Update"><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/></method>
  <signal name="UpdateAvailable"><arg type="a{sv}"/></signal><signal name="Progress"><arg type="a{sv}"/></signal>
</interface></node>"""


def serve(address, logpath):
    log = open(logpath, "a", buffering=1)

    def note(**kw):
        log.write(json.dumps(kw) + "\n")

    conn = Gio.DBusConnection.new_for_address_sync(
        address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
    conn.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                   GLib.Variant("(su)", (BUS_NAME, 4)), None, Gio.DBusCallFlags.NONE, 5000, None)
    monitor_iface = Gio.DBusNodeInfo.new_for_xml(MONITOR_XML).interfaces[0]

    def progress(path, client, steps):
        def send():
            if not steps:
                return False
            d = steps.pop(0)
            conn.emit_signal(client, path, MONITOR, "Progress", GLib.Variant("(a{sv})", (d,)))
            note(signal="Progress", **{k: v.unpack() for k, v in d.items()})
            return bool(steps)
        GLib.timeout_add(150, send)

    def monitor_call(conn_, sender, path, iface, method, params, inv):
        note(call=method, path=path, args=params.unpack())
        if method == "Update":
            inv.return_value(None)
            u = lambda x: GLib.Variant("u", x)
            progress(path, sender, [
                {"op": u(0), "n_ops": u(2), "progress": u(0), "status": u(0)},
                {"op": u(0), "n_ops": u(2), "progress": u(70), "status": u(0)},
                {"op": u(1), "n_ops": u(2), "progress": u(99), "status": u(0)},
                {"op": u(1), "n_ops": u(2), "progress": u(100), "status": u(2)},
            ])
        else:
            inv.return_value(None)

    def portal_call(conn_, sender, path, iface, method, params, inv):
        if method == "CreateUpdateMonitor":
            token = params.unpack()[0].get("handle_token", "1")
            mpath = "%s/update_monitor/%s/%s" % (PATH, sender[1:].replace(".", "_"), token)
            conn.register_object(mpath, monitor_iface, monitor_call, None, None)
            note(call=method, sender=sender, monitor=mpath)
            inv.return_value(GLib.Variant("(o)", (mpath,)))
        elif method == "Spawn":
            cwd, argv, fds, env, flags, opts = params.unpack()
            text = lambda b: bytes(b).rstrip(b"\0").decode()
            note(call=method, cwd=text(cwd), argv=[text(a) for a in argv], env=env, flags=flags)
            inv.return_value(GLib.Variant("(u)", (4242,)))
        else:
            inv.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method)

    def get_property(conn_, sender, path, iface, name):
        return GLib.Variant("u", 8 if name == "version" else 0)

    conn.register_object(PATH, Gio.DBusNodeInfo.new_for_xml(PORTAL_XML).interfaces[0], portal_call, get_property, None)
    note(ready=True)
    GLib.MainLoop().run()


def summary(pairs):
    refs = []
    for p in sorted(pairs):
        name, commit = p.split("=")
        refs.append((name, (1, bytes.fromhex(commit), {})))
    v = GLib.Variant("(a(s(taya{sv}))a{sv})", (refs, {}))
    sys.stdout.buffer.write(v.get_data_as_bytes().get_data())


if __name__ == "__main__":
    if sys.argv[1] == "serve":
        serve(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "summary":
        summary(sys.argv[2:])
