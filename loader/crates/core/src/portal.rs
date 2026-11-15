// SPDX-License-Identifier: GPL-3.0-or-later
//! The Flatpak portal (`org.freedesktop.portal.Flatpak`) over GIO's D-Bus:
//! an update monitor, `Update` with its `Progress`, and `Spawn` of the newest
//! build for the restart. Everything is asynchronous on the thread-default
//! main context (GTK's on Linux); what happens comes out as [`Event`]s, which
//! the app feeds to [`crate::flatpak_update::Updates`].
//!
//! Also here, because it needs GLib's GVariant: reading the commit of a ref
//! out of an OSTree `summary`, which is how Check now asks the remote.

use crate::fetch::{CancelToken, Http};
use crate::flatpak_update::Progress;
use gio::glib::{self, Variant, VariantTy};
use gio::prelude::*;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

pub const BUS_NAME: &str = "org.freedesktop.portal.Flatpak";
pub const PATH: &str = "/org/freedesktop/portal/Flatpak";
pub const IFACE: &str = "org.freedesktop.portal.Flatpak";
pub const MONITOR_IFACE: &str = "org.freedesktop.portal.Flatpak.UpdateMonitor";
/// `Spawn` flag: run the newest installed build of the app, not this one.
pub const SPAWN_LATEST_VERSION: u32 = 2;
/// The interface version that brought `CreateUpdateMonitor` (Flatpak 1.5; 1.6 is the first stable).
pub const MIN_VERSION: u32 = 2;

pub const NO_PORTAL: &str = "Updating from inside Godnotic needs the Flatpak portal (Flatpak 1.6 or newer)";

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    /// The monitor is up; `version` is the portal's interface version.
    Ready { version: u32 },
    /// No portal, or one too old for update monitors.
    Unavailable(String),
    UpdateAvailable { running: String, local: String, remote: String },
    Progress(Progress),
    /// The `Update` call itself was refused (another update running, no monitor).
    UpdateFailed(String),
    Spawned(u32),
    SpawnFailed(String),
}

pub struct Portal {
    conn: gio::DBusConnection,
    /// The monitor's path once created; until then the path the portal will give it.
    monitor: RefCell<String>,
    ready: RefCell<bool>,
    sink: Rc<dyn Fn(Event)>,
    _signals: RefCell<Option<gio::SignalSubscription>>,
}

/// The path the portal gives a monitor: its own path, the caller's unique name
/// (without the colon, dots as underscores) and the handle token.
pub fn monitor_path(unique_name: &str, token: &str) -> String {
    format!("{PATH}/update_monitor/{}/{token}", unique_name.trim_start_matches(':').replace('.', "_"))
}

/// "GDBus.Error:org.freedesktop.DBus.Error.Failed: Already installing" -> "Already installing".
pub fn error_text(e: &glib::Error) -> String {
    let m = e.message();
    match m.strip_prefix("GDBus.Error:").and_then(|r| r.split_once(": ")) {
        Some((_, text)) => text.to_string(),
        None => m.to_string(),
    }
}

fn empty_vardict() -> Variant {
    glib::VariantDict::new(None).end()
}

fn bytestring(s: &str) -> Variant {
    let mut b = s.as_bytes().to_vec();
    b.push(0);
    b.to_variant()
}

fn dict_str(d: &glib::VariantDict, key: &str) -> String {
    d.lookup::<String>(key).ok().flatten().unwrap_or_default()
}

fn dict_u32(d: &glib::VariantDict, key: &str) -> u32 {
    d.lookup::<u32>(key).ok().flatten().unwrap_or(0)
}

impl Portal {
    /// Asks the portal's version, creates an update monitor and listens to it.
    pub fn start(conn: &gio::DBusConnection, token: &str, sink: impl Fn(Event) + 'static) -> Rc<Self> {
        let unique = conn.unique_name().map(|s| s.to_string()).unwrap_or_default();
        let p = Rc::new(Self {
            conn: conn.clone(),
            monitor: RefCell::new(monitor_path(&unique, token)),
            ready: RefCell::new(false),
            sink: Rc::new(sink),
            _signals: RefCell::new(None),
        });
        let weak = Rc::downgrade(&p);
        let sub = conn.subscribe_to_signal(None, Some(MONITOR_IFACE), None, None, None, gio::DBusSignalFlags::NONE, move |s| {
            if let Some(p) = weak.upgrade() {
                if *p.monitor.borrow() == s.object_path {
                    p.on_signal(s.signal_name, s.parameters);
                }
            }
        });
        *p._signals.borrow_mut() = Some(sub);

        let (weak, token) = (Rc::downgrade(&p), token.to_string());
        conn.call(Some(BUS_NAME), PATH, "org.freedesktop.DBus.Properties", "Get", Some(&(IFACE, "version").to_variant()),
            Some(VariantTy::new("(v)").unwrap()), gio::DBusCallFlags::NONE, 10_000, None::<&gio::Cancellable>, move |r| {
                let Some(p) = weak.upgrade() else { return };
                let version = match r {
                    Ok(v) => v.child_value(0).as_variant().and_then(|v| v.get::<u32>()).unwrap_or(0),
                    Err(e) => {
                        (p.sink)(Event::Unavailable(format!("{NO_PORTAL}: {}", error_text(&e))));
                        return;
                    }
                };
                if version < MIN_VERSION {
                    (p.sink)(Event::Unavailable(format!("{NO_PORTAL}; this one is version {version}")));
                    return;
                }
                p.create_monitor(&token, version);
            });
        p
    }

    fn create_monitor(self: &Rc<Self>, token: &str, version: u32) {
        let opts = glib::VariantDict::new(None);
        opts.insert_value("handle_token", &token.to_variant());
        let weak = Rc::downgrade(self);
        self.conn.call(Some(BUS_NAME), PATH, IFACE, "CreateUpdateMonitor", Some(&Variant::tuple_from_iter([opts.end()])),
            Some(VariantTy::new("(o)").unwrap()), gio::DBusCallFlags::NONE, 10_000, None::<&gio::Cancellable>, move |r| {
                let Some(p) = weak.upgrade() else { return };
                match r {
                    Ok(v) => {
                        if let Some(path) = v.child_value(0).str() {
                            *p.monitor.borrow_mut() = path.to_string();
                        }
                        *p.ready.borrow_mut() = true;
                        (p.sink)(Event::Ready { version });
                    }
                    Err(e) => (p.sink)(Event::Unavailable(format!("{NO_PORTAL}: {}", error_text(&e)))),
                }
            });
    }

    fn on_signal(&self, name: &str, params: &Variant) {
        if params.n_children() < 1 {
            return;
        }
        let d = glib::VariantDict::new(Some(&params.child_value(0)));
        match name {
            "UpdateAvailable" => (self.sink)(Event::UpdateAvailable {
                running: dict_str(&d, "running-commit"),
                local: dict_str(&d, "local-commit"),
                remote: dict_str(&d, "remote-commit"),
            }),
            "Progress" => (self.sink)(Event::Progress(Progress {
                op: dict_u32(&d, "op"),
                n_ops: dict_u32(&d, "n_ops"),
                progress: dict_u32(&d, "progress"),
                status: dict_u32(&d, "status"),
                error: dict_str(&d, "error"),
                error_message: dict_str(&d, "error_message"),
            })),
            _ => {}
        }
    }

    pub fn is_ready(&self) -> bool {
        *self.ready.borrow()
    }

    /// Installs the newest build. `parent_window` as the portals take it ("" for none);
    /// the first time, the desktop asks the user whether Godnotic may update itself.
    pub fn update(self: &Rc<Self>, parent_window: &str) {
        let weak = Rc::downgrade(self);
        let path = self.monitor.borrow().clone();
        self.conn.call(Some(BUS_NAME), &path, MONITOR_IFACE, "Update", Some(&Variant::tuple_from_iter([parent_window.to_variant(), empty_vardict()])),
            Some(VariantTy::new("()").unwrap()), gio::DBusCallFlags::NONE, 30_000, None::<&gio::Cancellable>, move |r| {
                if let (Some(p), Err(e)) = (weak.upgrade(), r) {
                    (p.sink)(Event::UpdateFailed(error_text(&e)));
                }
            });
    }

    /// Starts the newest installed build in a new sandbox (the restart after an update).
    pub fn spawn_latest(self: &Rc<Self>, argv: &[&str], env: &HashMap<String, String>) {
        let weak = Rc::downgrade(self);
        let args = Variant::array_from_iter_with_type(VariantTy::BYTE_STRING, argv.iter().map(|a| bytestring(a)));
        let fds = Variant::array_from_iter_with_type(VariantTy::new("{uh}").unwrap(), std::iter::empty::<Variant>());
        let params = Variant::tuple_from_iter([bytestring("/"), args, fds, env.to_variant(), SPAWN_LATEST_VERSION.to_variant(), empty_vardict()]);
        self.conn.call(Some(BUS_NAME), PATH, IFACE, "Spawn", Some(&params), Some(VariantTy::new("(u)").unwrap()),
            gio::DBusCallFlags::NONE, 30_000, None::<&gio::Cancellable>, move |r| {
                let Some(p) = weak.upgrade() else { return };
                match r {
                    Ok(v) => (p.sink)(Event::Spawned(v.child_value(0).get::<u32>().unwrap_or(0))),
                    Err(e) => (p.sink)(Event::SpawnFailed(error_text(&e))),
                }
            });
    }

    /// Ends the monitor (and cancels an update in progress).
    pub fn close(&self) {
        if !self.is_ready() {
            return;
        }
        let path = self.monitor.borrow().clone();
        self.conn.call(Some(BUS_NAME), &path, MONITOR_IFACE, "Close", None, None, gio::DBusCallFlags::NONE, 2_000, None::<&gio::Cancellable>, |_| {});
    }
}

/// The commit (hex) an OSTree `summary` lists for `refname`.
pub fn summary_commit(summary: &[u8], refname: &str) -> Option<String> {
    let ty = VariantTy::new("(a(s(taya{sv}))a{sv})").ok()?;
    let v = Variant::from_bytes_with_type(&glib::Bytes::from(summary), ty);
    let refs = v.try_child_value(0)?;
    (0..refs.n_children()).map(|i| refs.child_value(i)).find(|e| e.child_value(0).str() == Some(refname)).and_then(|e| {
        let csum = e.child_value(1).child_value(1);
        let bytes = csum.fixed_array::<u8>().ok()?;
        (bytes.len() == 32).then(|| bytes.iter().map(|b| format!("{b:02x}")).collect())
    })
}

fn get_small(http: &dyn Http, url: &str, limit: usize) -> Result<Vec<u8>, String> {
    let mut code = 0;
    let mut out = Vec::new();
    let r = http.get(url, &[], &CancelToken::new(), &mut |h| {
        code = h.code;
        Ok(())
    }, &mut |b| {
        if out.len() + b.len() > limit {
            return Err(crate::fetch::Error::Fatal(format!("{url} is larger than expected")));
        }
        out.extend_from_slice(b);
        Ok(())
    });
    r.map_err(|e| e.to_string())?;
    if code != 200 {
        return Err(format!("The update server answered {code}"));
    }
    Ok(out)
}

/// Check now: the remote's commit for `refname`, and the newest version the
/// changelog names (None when that cannot be read; the commit is what counts).
pub fn check_remote(http: &dyn Http, remote_url: &str, refname: &str, changelog_url: &str) -> Result<(String, Option<String>), String> {
    let summary = get_small(http, &format!("{}/summary", remote_url.trim_end_matches('/')), 4 << 20)?;
    let commit = summary_commit(&summary, refname).ok_or_else(|| format!("The Flatpak remote does not list {refname}"))?;
    let newest = get_small(http, changelog_url, 1 << 20).ok()
        .and_then(|t| crate::flatpak_update::newest_in_changelog(&String::from_utf8_lossy(&t)));
    Ok((commit, newest))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_and_errors() {
        assert_eq!(monitor_path(":1.17081", "godnotic"), "/org/freedesktop/portal/Flatpak/update_monitor/1_17081/godnotic");
        let e = glib::Error::new(gio::IOErrorEnum::Failed, "GDBus.Error:org.freedesktop.DBus.Error.Failed: Already installing");
        assert_eq!(error_text(&e), "Already installing");
        let e = glib::Error::new(gio::IOErrorEnum::Failed, "plain");
        assert_eq!(error_text(&e), "plain");
    }

    #[test]
    fn reads_a_commit_out_of_a_summary() {
        // the shape ostree writes: (a(s(taya{sv}))a{sv}), refs sorted
        let entry = |name: &str, fill: u8| {
            let meta = glib::VariantDict::new(None);
            meta.insert_value("ostree.commit.timestamp", &1u64.to_variant());
            Variant::tuple_from_iter([name.to_variant(), Variant::tuple_from_iter([34_700_000u64.to_variant(), vec![fill; 32].to_variant(), meta.end()])])
        };
        let refs = Variant::array_from_iter_with_type(VariantTy::new("(s(taya{sv}))").unwrap(),
            [entry("app/io.github.dixonSolutions.Godnotic/aarch64/master", 0x69), entry("app/io.github.dixonSolutions.Godnotic/x86_64/master", 0x03)]);
        let s = Variant::tuple_from_iter([refs, empty_vardict()]);
        let data = s.data().to_vec();
        assert_eq!(summary_commit(&data, "app/io.github.dixonSolutions.Godnotic/x86_64/master").as_deref(), Some("03".repeat(32).as_str()));
        assert_eq!(summary_commit(&data, "app/io.github.dixonSolutions.Godnotic/aarch64/master").as_deref(), Some("69".repeat(32).as_str()));
        assert_eq!(summary_commit(&data, "app/other/x86_64/master"), None);
        assert_eq!(summary_commit(b"<html>not a summary</html>", "app/x"), None);
        assert_eq!(summary_commit(&[], "app/x"), None);
    }
}
