// SPDX-License-Identifier: GPL-3.0-or-later
//! Settings > Updates in the Flatpak: checks and installs through the Flatpak
//! portal (core `portal`, `flatpak_update`), and tells the menu through the
//! status file it already reads (qcsrc/menu/xonotic/touch_update_util.qh):
//!
//! - this process owns `<user data>/touch/update-status.txt` while it runs
//!   (start.sh, told by `GODNOTIC_UPDATE_STATUS=loader`, leaves it alone);
//! - the menu's requests (`check`, `install`, `restart`) arrive in
//!   update-request.txt, polled once a second and deleted when read;
//! - Check now reads the remote's summary and changelog (a few KB); the
//!   portal's own poll (every 30 minutes, unmetered networks only) and a
//!   `flatpak update` run elsewhere arrive as `UpdateAvailable`;
//! - Update now is the portal's `Update`, with progress; Restart to finish
//!   spawns the newest build through the portal and quits this one.
//!
//! Without a portal new enough (Flatpak before 1.6) the status says so and the
//! menu keeps the old text: GNOME Software, Discover or `flatpak update`.

use crate::app::App;
use crate::backend::Backend;
use godnotic_core::flatpak_update::{self, Instance, Phase, Request, Updates};
use godnotic_core::portal::{self, Event, Portal};
use gtk::prelude::*;
use gtk::{gio, glib};
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::{Rc, Weak};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

static OWNS_STATUS: AtomicBool = AtomicBool::new(false);

/// Set on the restarted instance's environment by the old one.
const RESTARTED: &str = "GODNOTIC_RESTARTED";

/// True once this process writes update-status.txt: the engine's start.sh must not.
pub fn owns_status() -> bool {
    OWNS_STATUS.load(Ordering::Relaxed)
}

/// The environment the engine starts with while this process owns the status.
pub fn engine_env() -> Option<(&'static str, &'static str)> {
    owns_status().then_some(("GODNOTIC_UPDATE_STATUS", "loader"))
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

struct Ctl {
    app: Weak<crate::app::Shared>,
    u: RefCell<Updates>,
    portal: RefCell<Option<Rc<Portal>>>,
    inst: Instance,
    status: PathBuf,
    requests: Vec<PathBuf>,
    written: RefCell<String>,
    remote: String,
    changelog: String,
}

/// The running build's version: version.txt (stamped by scripts/stage-flatpak.sh),
/// else the metainfo's newest release (builds before version.txt was stamped).
fn installed_version(app_root: &str) -> String {
    let v = std::fs::read_to_string(format!("{app_root}/share/xonotic/version.txt")).unwrap_or_default();
    let v = v.lines().next().unwrap_or("").trim().to_string();
    if !v.is_empty() {
        return v;
    }
    std::fs::read_to_string(format!("{app_root}/share/metainfo/{}.metainfo.xml", godnotic_core::session::APP_ID)).ok()
        .and_then(|x| flatpak_update::version_from_metainfo(&x)).unwrap_or_default()
}

/// Starts watching for updates when this is a Flatpak (`/.flatpak-info`).
/// `GODNOTIC_FLATPAK_INFO` points tests at another file.
pub fn start(app: &App) {
    if std::env::var_os("GODNOTIC_UPDATES_OFF").is_some() {
        return;
    }
    let info_path = std::env::var("GODNOTIC_FLATPAK_INFO").unwrap_or_else(|_| "/.flatpak-info".into());
    let Some(inst) = std::fs::read_to_string(&info_path).ok().and_then(|t| Instance::parse(&t)) else { return };
    let (paths, _) = crate::session_ui::paths();
    let app_root = paths.app_root.clone().unwrap_or_else(|| "/app".into());
    let user_touch = Path::new(&paths.user_data()).join("touch");
    let requests = vec![
        user_touch.join("update-request.txt"),
        // QuakeC's FILE_WRITE lands in the engine's userdir (see data_ui.rs)
        Path::new(&paths.engine_user_dir).join("data/data/touch/update-request.txt"),
    ];
    for r in &requests {
        let _ = std::fs::remove_file(r); // a request left by an earlier session is not this one's
    }
    let env = |k: &str, d: &str| std::env::var(k).ok().filter(|s| !s.is_empty()).unwrap_or_else(|| d.to_string());
    let ctl = Rc::new(Ctl {
        app: Rc::downgrade(app),
        u: RefCell::new(Updates::new(&installed_version(&app_root), &inst.commit)),
        portal: RefCell::new(None),
        inst,
        status: user_touch.join("update-status.txt"),
        requests,
        written: RefCell::new(String::new()),
        remote: env("GODNOTIC_FLATPAK_REMOTE", flatpak_update::REMOTE_URL),
        changelog: env("GODNOTIC_CHANGELOG_URL", flatpak_update::CHANGELOG_URL),
    });
    OWNS_STATUS.store(true, Ordering::Relaxed);
    ctl.write();

    let weak = Rc::downgrade(&ctl);
    gio::bus_get(gio::BusType::Session, None::<&gio::Cancellable>, move |r| {
        let Some(c) = weak.upgrade() else { return };
        match r {
            Ok(conn) => {
                let w = Rc::downgrade(&c);
                let p = Portal::start(&conn, "godnotic", move |e| {
                    if let Some(c) = w.upgrade() {
                        c.on_event(e);
                    }
                });
                *c.portal.borrow_mut() = Some(p);
            }
            Err(e) => c.on_event(Event::Unavailable(format!("{}: {}", portal::NO_PORTAL, portal::error_text(&e)))),
        }
    });

    // requests once a second; the status again every 30 s so "checked N min ago" stays true,
    // or at once if something else wrote over it
    let mut ticks = 0u32;
    glib::timeout_add_local(Duration::from_secs(1), move || {
        ticks += 1;
        for r in ctl.requests.clone() {
            if let Ok(text) = std::fs::read_to_string(&r) {
                let _ = std::fs::remove_file(&r);
                if let Some(req) = Request::parse(&text) {
                    ctl.request(req);
                }
            }
        }
        let clobbered = std::fs::read_to_string(&ctl.status).map(|t| t != *ctl.written.borrow()).unwrap_or(true);
        if clobbered || ticks.is_multiple_of(30) {
            ctl.write();
        }
        // a first check shortly after start, unless the network is metered (as the portal does)
        if ticks == 8 && !gio::NetworkMonitor::default().is_network_metered() {
            ctl.request(Request::Check);
        }
        glib::ControlFlow::Continue
    });
}

impl Ctl {
    fn write(&self) {
        let text = self.u.borrow().status_text(now());
        if let Some(d) = self.status.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let tmp = self.status.with_extension("txt.tmp");
        if std::fs::write(&tmp, &text).is_ok() && std::fs::rename(&tmp, &self.status).is_ok() {
            *self.written.borrow_mut() = text;
        }
    }

    fn on_event(self: &Rc<Self>, e: Event) {
        let t = now();
        {
            let mut u = self.u.borrow_mut();
            match &e {
                Event::Ready { .. } => {}
                Event::Unavailable(why) => {
                    eprintln!("godnotic-loader: updates: {why}");
                    u.portal_unavailable(&format!("{why}."));
                }
                Event::UpdateAvailable { running, local, remote } => u.update_available(t, running, local, remote),
                Event::Progress(p) => u.progress(t, p.clone()),
                Event::UpdateFailed(why) => u.update_failed(why),
                Event::Spawned(pid) => {
                    eprintln!("godnotic-loader: the new version started (pid {pid}); this one quits");
                    drop(u);
                    self.quit();
                    return;
                }
                Event::SpawnFailed(why) => u.update_failed(&format!("Could not start the new version ({why}): close Godnotic and open it again")),
            }
        }
        self.write();
    }

    fn request(self: &Rc<Self>, req: Request) {
        let Some(p) = self.portal.borrow().clone().filter(|p| p.is_ready()) else {
            return; // no monitor (yet): the menu shows the package-manager text
        };
        match req {
            Request::Check => {
                if !self.u.borrow_mut().check_started() {
                    return;
                }
                self.write();
                let (remote, changelog, refname) = (self.remote.clone(), self.changelog.clone(), self.inst.app_ref());
                let weak = Rc::downgrade(self);
                glib::spawn_future_local(async move {
                    let r = gio::spawn_blocking(move || {
                        portal::check_remote(&godnotic_core::fetch::http_ureq::UreqHttp::new(), &remote, &refname, &changelog)
                    }).await;
                    let Some(c) = weak.upgrade() else { return };
                    let t = now();
                    match r {
                        Ok(Ok((commit, newest))) => c.u.borrow_mut().check_done(t, &commit, newest.as_deref()),
                        Ok(Err(e)) => c.u.borrow_mut().check_failed(t, &e),
                        Err(_) => c.u.borrow_mut().check_failed(t, "The check stopped unexpectedly"),
                    }
                    c.write();
                });
            }
            Request::Install => {
                if self.u.borrow_mut().update_started() {
                    self.write();
                    p.update("");
                }
            }
            Request::Restart => {
                if self.u.borrow().phase == Phase::Restart {
                    p.spawn_latest(&["godnotic-loader"], &HashMap::from([(RESTARTED.to_string(), "1".to_string())]));
                }
            }
        }
    }

    /// The new build is starting: the engine goes, then this process.
    fn quit(&self) {
        if let Some(p) = self.portal.borrow().as_ref() {
            p.close();
        }
        if let Some(a) = self.app.upgrade() {
            if let Backend::Engine(e) = &mut *a.backend.borrow_mut() {
                let _ = e.proc.child.kill();
                let _ = e.proc.child.wait();
            }
        }
        let _ = std::fs::remove_file(&self.status);
        if let Some(app) = gio::Application::default() {
            app.quit();
        }
    }
}

/// The instance a restart started waits until the old one has left the bus, or
/// GApplication would hand it straight back to the old process.
pub fn wait_for_old_instance() {
    if std::env::var_os(RESTARTED).is_none() {
        return;
    }
    let Ok(conn) = gio::bus_get_sync(gio::BusType::Session, None::<&gio::Cancellable>) else { return };
    let t = std::time::Instant::now();
    while t.elapsed() < Duration::from_secs(10) {
        let owned = conn.call_sync(Some("org.freedesktop.DBus"), "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
            Some(&(godnotic_core::session::APP_ID,).to_variant()), Some(glib::VariantTy::new("(b)").unwrap()),
            gio::DBusCallFlags::NONE, 1000, None::<&gio::Cancellable>)
            .ok().and_then(|v| v.child_value(0).get::<bool>()).unwrap_or(false);
        if !owned {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
