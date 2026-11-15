// SPDX-License-Identifier: GPL-3.0-or-later
//! The Flatpak update path against a fake portal: a private `dbus-daemon`, a
//! stub `org.freedesktop.portal.Flatpak` on it (version property, update
//! monitors, Update with Progress, Spawn), and the real client
//! (`godnotic_core::portal`) driving the state machine
//! (`godnotic_core::flatpak_update`) the way the Linux app does. Each scenario
//! ends by checking the update-status.txt the menu would read.

use gio::glib::{self, Variant, VariantTy};
use gio::prelude::*;
use godnotic_core::fetch::{CancelToken, Head, Http};
use godnotic_core::flatpak_update::{Phase, Updates};
use godnotic_core::portal::{self, Event, Portal};
use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::rc::Rc;
use std::time::{Duration, Instant};

const OLD: &str = "03c3d5707b0f186f2023d3e0ba1811bbb79539d4c9ca7c810862a30610e2e615";
const NEW: &str = "6909a2fc6c979219dde636c8d242cb8d67efc93046f5ec66f41b1b83651ce731";

const PORTAL_XML: &str = r#"<node>
  <interface name="org.freedesktop.portal.Flatpak">
    <property name="version" type="u" access="read"/>
    <property name="supports" type="u" access="read"/>
    <method name="CreateUpdateMonitor"><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
    <method name="Spawn">
      <arg type="ay" name="cwd_path" direction="in"/><arg type="aay" name="argv" direction="in"/>
      <arg type="a{uh}" name="fds" direction="in"/><arg type="a{ss}" name="envs" direction="in"/>
      <arg type="u" name="flags" direction="in"/><arg type="a{sv}" name="options" direction="in"/>
      <arg type="u" name="pid" direction="out"/>
    </method>
  </interface>
</node>"#;

const MONITOR_XML: &str = r#"<node>
  <interface name="org.freedesktop.portal.Flatpak.UpdateMonitor">
    <method name="Close"/>
    <method name="Update"><arg type="s" name="parent_window" direction="in"/><arg type="a{sv}" name="options" direction="in"/></method>
    <signal name="UpdateAvailable"><arg type="a{sv}" name="update_info"/></signal>
    <signal name="Progress"><arg type="a{sv}" name="info"/></signal>
  </interface>
</node>"#;

/// A private session bus: no service files, so nothing real is ever activated.
struct Bus {
    child: Child,
    address: String,
    _dir: TempDir,
}

impl Drop for Bus {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct TempDir(std::path::PathBuf);
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn bus() -> Bus {
    let dir = std::env::temp_dir().join(format!("godnotic-portal-test-{}-{}", std::process::id(), Instant::now().elapsed().as_nanos() ^ rand_ish()));
    std::fs::create_dir_all(&dir).unwrap();
    let conf = dir.join("bus.conf");
    std::fs::write(&conf, format!(
        "<busconfig><type>session</type><listen>unix:dir={}</listen><auth>EXTERNAL</auth>\
         <policy context=\"default\"><allow send_destination=\"*\" eavesdrop=\"true\"/><allow eavesdrop=\"true\"/><allow own=\"*\"/></policy></busconfig>",
        dir.display())).unwrap();
    let mut child = Command::new("dbus-daemon")
        .args(["--nofork", "--nopidfile", "--print-address=1", &format!("--config-file={}", conf.display())])
        .stdout(Stdio::piped()).stderr(Stdio::inherit()).spawn()
        .expect("dbus-daemon (package dbus) is needed for the portal tests");
    let mut line = String::new();
    BufReader::new(child.stdout.take().unwrap()).read_line(&mut line).unwrap();
    assert!(line.starts_with("unix:"), "dbus-daemon printed {line:?}");
    Bus { child, address: line.trim().to_string(), _dir: TempDir(dir) }
}

fn rand_ish() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
}

fn connect(b: &Bus) -> gio::DBusConnection {
    gio::DBusConnection::for_address_sync(&b.address,
        gio::DBusConnectionFlags::AUTHENTICATION_CLIENT | gio::DBusConnectionFlags::MESSAGE_BUS_CONNECTION,
        None, None::<&gio::Cancellable>).unwrap()
}

/// Runs the thread-default main context until `done` or the deadline.
fn spin(what: &str, done: impl Fn() -> bool) {
    let ctx = glib::MainContext::ref_thread_default();
    let t = Instant::now();
    while !done() {
        assert!(t.elapsed() < Duration::from_secs(10), "timed out waiting for {what}");
        if !ctx.iteration(false) {
            std::thread::sleep(Duration::from_millis(2));
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum UpdateMode {
    Succeeds,
    NeedsPermissions,
    Refused,
}

#[derive(Default)]
struct Calls {
    windows: Vec<String>,
    spawned: Vec<(Vec<String>, u32, HashMap<String, String>)>,
    closed: u32,
}

/// The stub portal: owns the name, answers `version`, makes monitors.
fn stub(conn: &gio::DBusConnection, version: u32, mode: UpdateMode, announce: Option<(&'static str, &'static str, &'static str)>) -> Rc<RefCell<Calls>> {
    conn.call_sync(Some("org.freedesktop.DBus"), "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
        Some(&(portal::BUS_NAME, 4u32).to_variant()), None, gio::DBusCallFlags::NONE, 5000, None::<&gio::Cancellable>).unwrap();
    let calls = Rc::new(RefCell::new(Calls::default()));
    let node = gio::DBusNodeInfo::for_xml(PORTAL_XML).unwrap();
    let iface = node.lookup_interface(portal::IFACE).unwrap();
    let c = calls.clone();
    let _id = conn.register_object(portal::PATH, &iface)
        .method_call(move |conn, sender, _path, _iface, method, params, inv| {
            let sender = sender.unwrap_or_default().to_string();
            match method {
                "CreateUpdateMonitor" => {
                    let opts = glib::VariantDict::new(Some(&params.child_value(0)));
                    let token = opts.lookup::<String>("handle_token").ok().flatten().unwrap_or_else(|| "1".into());
                    let path = portal::monitor_path(&sender, &token);
                    register_monitor(&conn, &path, &sender, mode, c.clone());
                    inv.return_value(Some(&Variant::tuple_from_iter([glib::variant::ObjectPath::try_from(path.clone()).unwrap().to_variant()])));
                    if let Some((r, l, m)) = announce {
                        let conn = conn.clone();
                        later(1, move || emit(&conn, &sender, &path, "UpdateAvailable", &[("running-commit", r.to_variant()), ("local-commit", l.to_variant()), ("remote-commit", m.to_variant())]));
                    }
                }
                "Spawn" => {
                    let argv: Vec<String> = (0..params.child_value(1).n_children()).map(|i| {
                        let b = params.child_value(1).child_value(i).fixed_array::<u8>().unwrap().to_vec();
                        assert_eq!(b.last(), Some(&0), "argv entries are NUL-terminated bytestrings");
                        String::from_utf8_lossy(&b[..b.len() - 1]).to_string()
                    }).collect();
                    let cwd = params.child_value(0).fixed_array::<u8>().unwrap().to_vec();
                    assert_eq!(cwd, b"/\0");
                    let env: HashMap<String, String> = params.child_value(3).get().unwrap();
                    c.borrow_mut().spawned.push((argv, params.child_value(4).get::<u32>().unwrap(), env));
                    inv.return_value(Some(&(4242u32,).to_variant()));
                }
                _ => inv.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method),
            }
        })
        .property(move |_, _, _, _, name| match name {
            "version" => version.to_variant(),
            _ => 0u32.to_variant(),
        })
        .build().unwrap();
    calls
}

/// On the thread-default context (the glib::*_add_local helpers use the global one).
fn later(ms: u64, f: impl FnOnce() + 'static) {
    glib::MainContext::ref_thread_default().spawn_local(async move {
        glib::timeout_future(Duration::from_millis(ms)).await;
        f();
    });
}

fn emit(conn: &gio::DBusConnection, dest: &str, path: &str, signal: &str, entries: &[(&str, Variant)]) {
    let d = glib::VariantDict::new(None);
    for (k, v) in entries {
        d.insert_value(k, v);
    }
    conn.emit_signal(Some(dest), path, portal::MONITOR_IFACE, signal, Some(&Variant::tuple_from_iter([d.end()]))).unwrap();
}

fn register_monitor(conn: &gio::DBusConnection, path: &str, client: &str, mode: UpdateMode, calls: Rc<RefCell<Calls>>) {
    let node = gio::DBusNodeInfo::for_xml(MONITOR_XML).unwrap();
    let iface = node.lookup_interface(portal::MONITOR_IFACE).unwrap();
    let client = client.to_string();
    let _id = conn.register_object(path, &iface)
        .method_call(move |conn, _sender, path, _iface, method, params, inv| match method {
            "Update" => {
                if mode == UpdateMode::Refused {
                    inv.return_dbus_error("org.freedesktop.DBus.Error.Failed", "Already installing");
                    return;
                }
                calls.borrow_mut().windows.push(params.child_value(0).get::<String>().unwrap());
                inv.return_value(None);
                // what flatpak-portal sends: per operation 0..99, 100 when done, then the status
                let steps: Vec<Vec<(&str, Variant)>> = match mode {
                    UpdateMode::Succeeds => vec![
                        vec![("op", 0u32.to_variant()), ("n_ops", 2u32.to_variant()), ("progress", 0u32.to_variant()), ("status", 0u32.to_variant())],
                        vec![("op", 0u32.to_variant()), ("n_ops", 2u32.to_variant()), ("progress", 60u32.to_variant()), ("status", 0u32.to_variant())],
                        vec![("op", 1u32.to_variant()), ("n_ops", 2u32.to_variant()), ("progress", 99u32.to_variant()), ("status", 0u32.to_variant())],
                        vec![("op", 1u32.to_variant()), ("n_ops", 2u32.to_variant()), ("progress", 100u32.to_variant()), ("status", 2u32.to_variant())],
                    ],
                    _ => vec![
                        vec![("op", 0u32.to_variant()), ("n_ops", 1u32.to_variant()), ("progress", 0u32.to_variant()), ("status", 0u32.to_variant())],
                        vec![("status", 3u32.to_variant()), ("error", "org.freedesktop.DBus.Error.NotSupported".to_variant()),
                             ("error_message", "Self update not supported, new version requires new permissions".to_variant())],
                    ],
                };
                let (conn, client, path) = (conn.clone(), client.clone(), path.to_string());
                for (i, s) in steps.into_iter().enumerate() {
                    let (conn, client, path) = (conn.clone(), client.clone(), path.clone());
                    later(20 * (i as u64 + 1), move || emit(&conn, &client, &path, "Progress", &s));
                }
            }
            "Close" => {
                calls.borrow_mut().closed += 1;
                inv.return_value(None);
            }
            _ => inv.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method),
        })
        .build().unwrap();
}

/// The app's glue, as in loader/crates/gtk/src/updates.rs: events into the state machine.
fn client(conn: &gio::DBusConnection, u: Rc<RefCell<Updates>>, now: u64) -> (Rc<Portal>, Rc<RefCell<Vec<Event>>>) {
    let seen = Rc::new(RefCell::new(Vec::new()));
    let s = seen.clone();
    let p = Portal::start(conn, "godnotic", move |e| {
        let mut u = u.borrow_mut();
        match &e {
            Event::Unavailable(why) => u.portal_unavailable(why),
            Event::UpdateAvailable { running, local, remote } => u.update_available(now, running, local, remote),
            Event::Progress(p) => u.progress(now, p.clone()),
            Event::UpdateFailed(why) => u.update_failed(why),
            _ => {}
        }
        s.borrow_mut().push(e);
    });
    (p, seen)
}

fn lines(u: &Rc<RefCell<Updates>>, now: u64) -> Vec<String> {
    u.borrow().status_text(now).lines().map(str::to_string).collect()
}

#[test]
fn portal_state_machine_over_a_fake_portal() {
    let ctx = glib::MainContext::new();
    let r = ctx.with_thread_default(|| {
        // 1. The monitor reports a newer build; Update now installs it with progress; restart.
        {
            let b = bus();
            let (server, app) = (connect(&b), connect(&b));
            let calls = stub(&server, 8, UpdateMode::Succeeds, Some((OLD, OLD, NEW)));
            let u = Rc::new(RefCell::new(Updates::new("1.2.205", OLD)));
            u.borrow_mut().latest = "1.2.206".into(); // as Check now's changelog read would say
            let (p, seen) = client(&app, u.clone(), 1000);
            spin("the monitor", || p.is_ready());
            assert!(seen.borrow().contains(&Event::Ready { version: 8 }));
            spin("UpdateAvailable", || u.borrow().phase == Phase::Available);
            let l = lines(&u, 1000);
            assert_eq!(&l[..6], &["1", "available", "1.2.205", "1.2.206", "1", "0"]);
            assert!(l.contains(&"via=flatpak".to_string()) && l.contains(&"portal=yes".to_string()));

            assert!(u.borrow_mut().update_started());
            p.update("");
            spin("progress", || matches!(&u.borrow().phase, Phase::Updating(pr) if pr.progress == 60));
            assert_eq!(lines(&u, 1000)[1], "downloading");
            assert_eq!(lines(&u, 1000)[5], "30", "op 1 of 2 at 60%: 30% of the whole");
            spin("the end of the update", || u.borrow().phase == Phase::Restart);
            let l = lines(&u, 1000);
            assert_eq!(&l[1..4], &["ready", "1.2.205", "1.2.206"]);
            assert_eq!(calls.borrow().windows, vec![String::new()]);

            let env = HashMap::from([("GODNOTIC_RESTARTED".to_string(), "1".to_string())]);
            p.spawn_latest(&["godnotic-loader"], &env);
            spin("Spawn", || seen.borrow().contains(&Event::Spawned(4242)));
            let c = calls.borrow();
            assert_eq!(c.spawned.len(), 1);
            assert_eq!(c.spawned[0].0, vec!["godnotic-loader".to_string()]);
            assert_eq!(c.spawned[0].1, portal::SPAWN_LATEST_VERSION);
            assert_eq!(c.spawned[0].2.get("GODNOTIC_RESTARTED").map(String::as_str), Some("1"));
            drop(c);
            p.close();
            spin("Close", || calls.borrow().closed == 1);
        }
        // 2. An update that needs new permissions fails with the portal's words.
        {
            let b = bus();
            let (server, app) = (connect(&b), connect(&b));
            let _calls = stub(&server, 8, UpdateMode::NeedsPermissions, None);
            let u = Rc::new(RefCell::new(Updates::new("1.2.205", OLD)));
            let (p, _) = client(&app, u.clone(), 50);
            spin("the monitor", || p.is_ready());
            assert_eq!(u.borrow().phase, Phase::Idle, "no signal yet: not checked");
            u.borrow_mut().update_started();
            p.update("");
            spin("the error", || matches!(u.borrow().phase, Phase::Error(_)));
            let l = lines(&u, 50);
            assert_eq!(l[1], "error");
            assert_eq!(l[6], "Self update not supported, new version requires new permissions");
        }
        // 3. The Update call refused outright.
        {
            let b = bus();
            let (server, app) = (connect(&b), connect(&b));
            let _calls = stub(&server, 8, UpdateMode::Refused, None);
            let u = Rc::new(RefCell::new(Updates::new("1.2.205", OLD)));
            let (p, _) = client(&app, u.clone(), 50);
            spin("the monitor", || p.is_ready());
            u.borrow_mut().update_started();
            p.update("");
            spin("the refusal", || matches!(u.borrow().phase, Phase::Error(_)));
            assert_eq!(lines(&u, 50)[6], "Already installing");
        }
        // 4. A portal too old for update monitors, and no portal at all: the old text stays.
        for version in [Some(1u32), None] {
            let b = bus();
            let app = connect(&b);
            let _server = version.map(|v| {
                let s = connect(&b);
                stub(&s, v, UpdateMode::Succeeds, None);
                s
            });
            let u = Rc::new(RefCell::new(Updates::new("1.2.205", OLD)));
            let (p, seen) = client(&app, u.clone(), 0);
            spin("the verdict", || matches!(u.borrow().phase, Phase::Unavailable(_)));
            assert!(!p.is_ready());
            assert!(matches!(seen.borrow()[0], Event::Unavailable(_)));
            let l = lines(&u, 0);
            assert_eq!(l[1], "managed");
            assert!(l.contains(&"portal=none".to_string()));
            assert!(l[6].contains("Flatpak 1.6"), "{}", l[6]);
            assert!(!u.borrow_mut().check_started());
        }
    });
    r.expect("the test's main context");
}

/// Serves fixed bodies by URL.
struct FakeHttp(HashMap<String, (u16, Vec<u8>)>);

impl Http for FakeHttp {
    fn get(&self, url: &str, _h: &[(&str, String)], _c: &CancelToken, head: &mut dyn FnMut(&Head) -> godnotic_core::fetch::Result<()>,
           body: &mut dyn FnMut(&[u8]) -> godnotic_core::fetch::Result<()>) -> godnotic_core::fetch::Result<()> {
        let (code, data) = self.0.get(url).cloned().unwrap_or((404, b"not found".to_vec()));
        head(&Head { code, headers: Default::default() })?;
        body(&data)
    }
}

fn summary(refs: &[(&str, &str)]) -> Vec<u8> {
    let entries = refs.iter().map(|(name, hex)| {
        let bytes: Vec<u8> = (0..32).map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).unwrap()).collect();
        Variant::tuple_from_iter([name.to_variant(), Variant::tuple_from_iter([1u64.to_variant(), bytes.to_variant(), glib::VariantDict::new(None).end()])])
    });
    let refs = Variant::array_from_iter_with_type(VariantTy::new("(s(taya{sv}))").unwrap(), entries);
    Variant::tuple_from_iter([refs, glib::VariantDict::new(None).end()]).data().to_vec()
}

#[test]
fn check_now_reads_the_remote_summary_and_the_changelog() {
    let r = "https://example.invalid/flatpak";
    let refname = "app/io.github.dixonSolutions.Godnotic/x86_64/master";
    let http = FakeHttp(HashMap::from([
        (format!("{r}/summary"), (200, summary(&[("app/io.github.dixonSolutions.Godnotic/aarch64/master", OLD), (refname, NEW)]))),
        ("https://example.invalid/changelog.txt".to_string(), (200, b"1.2.206\n- Faster joins\n\n1.2.205\n- x\n".to_vec())),
    ]));
    let (commit, newest) = portal::check_remote(&http, r, refname, "https://example.invalid/changelog.txt").unwrap();
    assert_eq!((commit.as_str(), newest.as_deref()), (NEW, Some("1.2.206")));

    let mut u = Updates::new("1.2.205", OLD);
    assert!(u.check_started());
    u.check_done(10, &commit, newest.as_deref());
    assert_eq!(u.phase, Phase::Available);
    assert_eq!(u.latest, "1.2.206");

    // the running build is the remote's: up to date, whatever the changelog says
    let mut v = Updates::new("1.2.206", NEW);
    v.check_done(10, &commit, newest.as_deref());
    assert_eq!(v.phase, Phase::UpToDate);

    // no changelog: the commit still decides
    let http2 = FakeHttp(HashMap::from([(format!("{r}/summary"), (200, summary(&[(refname, NEW)])))]));
    assert_eq!(portal::check_remote(&http2, r, refname, "https://example.invalid/changelog.txt").unwrap(), (NEW.to_string(), None));
    // a remote without this arch, and one that is down, are errors that say so
    let e = portal::check_remote(&http2, r, "app/io.github.dixonSolutions.Godnotic/riscv64/master", "x").unwrap_err();
    assert!(e.contains("does not list"), "{e}");
    let e = portal::check_remote(&FakeHttp(HashMap::new()), r, refname, "x").unwrap_err();
    assert!(e.contains("404"), "{e}");
}
