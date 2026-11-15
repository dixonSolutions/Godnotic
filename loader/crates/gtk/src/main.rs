// SPDX-License-Identifier: GPL-3.0-or-later
//! godnotic-loader: the Godnotic app on Linux. One window, up at once with the
//! native skeleton (banner and placeholders); then the engine's UI bridge (or
//! a fixture) fills it with the described screens, rendered with libadwaita.

mod app;
mod backend;
mod data_ui;
mod images;
mod lists;
mod script;
mod session_ui;
mod widgets;

use adw::prelude::*;
use backend::{Backend, FixtureBackend};
use gtk::{gdk, gio, glib};
use serde_json::json;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::rc::Rc;

const APP_ID: &str = "io.github.dixonSolutions.Godnotic";

const HELP: &str = "Usage: godnotic-loader [OPTION]... [godnotic://join/HOST:PORT]
  --fixture=NAME|PATH   render a UI-bridge fixture (tests/uibridge/fixtures) instead of the engine
  --fixtures-dir=DIR    where fixture names are looked up
  --size=WxH            window size
  --dark, --light       force the colour scheme (default: the system's)
  --reduced-motion      no animations
  --snapshot=FILE.png   render the window to a PNG after --snapshot-delay ms and quit
  --script=FILE         drive the UI (wait, click, row, back, snapshot, size, quit): tests
  --time                print start-up timings
";

#[derive(Default, Clone)]
struct Opts {
    fixture: Option<String>,
    fixtures_dir: Option<PathBuf>,
    size: Option<(i32, i32)>,
    scheme: Option<adw::ColorScheme>,
    reduced: bool,
    snapshot: Option<String>,
    snapshot_delay: u64,
    script: Option<String>,
    time: bool,
    link: Option<String>,
}

fn parse_args() -> Opts {
    let mut o = Opts { snapshot_delay: 1200, ..Default::default() };
    for a in std::env::args().skip(1) {
        let (k, v) = a.split_once('=').map(|(k, v)| (k.to_string(), v.to_string())).unwrap_or((a.clone(), String::new()));
        match k.as_str() {
            "--help" | "-h" => {
                print!("{HELP}");
                std::process::exit(0);
            }
            "--fixture" => o.fixture = Some(v),
            "--fixtures-dir" => o.fixtures_dir = Some(v.into()),
            "--size" => o.size = v.split_once('x').and_then(|(w, h)| Some((w.parse().ok()?, h.parse().ok()?))),
            "--dark" => o.scheme = Some(adw::ColorScheme::ForceDark),
            "--light" => o.scheme = Some(adw::ColorScheme::ForceLight),
            "--reduced-motion" => o.reduced = true,
            "--snapshot" => o.snapshot = Some(v),
            "--snapshot-delay" => o.snapshot_delay = v.parse().unwrap_or(1200),
            "--script" => o.script = Some(v),
            "--time" => o.time = true,
            _ if a.starts_with("godnotic:") => o.link = Some(a),
            _ => eprintln!("godnotic-loader: unknown argument {a}"),
        }
    }
    o
}

fn fixtures_dir(o: &Opts) -> PathBuf {
    if let Some(d) = &o.fixtures_dir {
        return d.clone();
    }
    if let Some(f) = &o.fixture {
        let p = PathBuf::from(f);
        if p.is_file() {
            return p.parent().map(PathBuf::from).unwrap_or_default();
        }
    }
    std::env::var_os("GODNOTIC_FIXTURES").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../tests/uibridge/fixtures")))
}

fn make_backend(o: &Opts) -> Backend {
    if let Some(f) = &o.fixture {
        let dir = fixtures_dir(o);
        let name = PathBuf::from(f).file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| f.clone());
        let name = if name.ends_with(".json") { name } else { format!("{name}.json") };
        return match backend::load_fixture(&dir, &name) {
            Ok(player) => Backend::Fixture(Box::new(FixtureBackend { builtin: false, dir, player, current: name, history: vec![], incoming: VecDeque::new() })),
            Err(e) => Backend::Offline(format!("Could not read the fixture: {e}")),
        };
    }
    // The engine's UI-bridge backend mode lands with work/uibridge; until then the
    // app shows its own screens and opens the game's menu for the rest.
    match backend::load_builtin("home.json") {
        Ok(player) => Backend::Fixture(Box::new(FixtureBackend { builtin: true, dir: PathBuf::new(), player, current: "home.json".into(), history: vec![], incoming: VecDeque::new() })),
        Err(e) => Backend::Offline(e),
    }
}

fn css() {
    let p = gtk::CssProvider::new();
    p.load_from_string(include_str!("../data/style.css"));
    if let Some(d) = gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&d, &p, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
}

fn main() -> glib::ExitCode {
    let t0 = std::time::Instant::now();
    // WM_CLASS on X11 follows the program name: the app id, as the desktop file's StartupWMClass says.
    glib::set_prgname(Some(APP_ID));
    godnotic_core::paths::GamePaths::adopt_old_name(&std::env::vars().collect());
    let o = parse_args();
    embed_backend_choice(&o);
    let test_run = o.snapshot.is_some() || o.script.is_some() || std::env::var_os("GODNOTIC_EXIT_AFTER_FRAME").is_some() || std::env::var_os("GODNOTIC_NON_UNIQUE").is_some();
    let flags = if test_run { gio::ApplicationFlags::NON_UNIQUE } else { gio::ApplicationFlags::HANDLES_OPEN };
    let gapp = adw::Application::builder().application_id(APP_ID).flags(flags).build();
    let state: Rc<std::cell::RefCell<Option<app::App>>> = Rc::new(std::cell::RefCell::new(None));

    let (st, o2) = (state.clone(), o.clone());
    gapp.connect_activate(move |gapp| {
        if let Some(a) = st.borrow().as_ref() {
            if let Some(u) = a.ui.borrow().as_ref() {
                u.win.present();
            }
            return;
        }
        css();
        if let Some(s) = o2.scheme {
            adw::StyleManager::default().set_color_scheme(s);
        }
        let a = app::Shared::new(make_backend(&o2));
        a.reduced_motion.set(o2.reduced || !gtk::Settings::default().map(|s| s.is_gtk_enable_animations()).unwrap_or(true));
        a.images.borrow_mut().preload_brand();
        let win = app::build_window(&a, gapp, o2.size);
        if (!test_run || std::env::var_os("GODNOTIC_TEST_FULLSCREEN").is_some()) && o2.size.is_none() {
            win.fullscreen();
        }
        if a.reduced_motion.get() {
            win.add_css_class("gn-reduced");
        }
        let (a2, o3) = (a.clone(), o2.clone());
        let first = std::cell::Cell::new(true);
        win.add_tick_callback(move |w, _| {
            if !first.replace(false) {
                return glib::ControlFlow::Break;
            }
            let ms = t0.elapsed().as_secs_f64() * 1000.0;
            if o3.time || std::env::var_os("GODNOTIC_TIMING").is_some() {
                eprintln!("godnotic-loader: first frame after {ms:.1} ms (process start)");
            }
            if std::env::var_os("GODNOTIC_EXIT_AFTER_FRAME").is_some() {
                if let Some(app) = w.application() {
                    app.quit();
                }
                return glib::ControlFlow::Break;
            }
            // The described UI comes after the first frame: nothing blocks the window.
            let real_engine = !test_run || std::env::var_os("GODNOTIC_TEST_NATIVE_BACKEND").is_some();
            if o3.fixture.is_none() && std::env::var_os("GODNOTIC_SKIP_DATA_CHECK").is_none() && !data_ui::ready() {
                show_first_download(&a2, real_engine);
            } else {
                start_ui(&a2, real_engine);
            }
            if let Some(path) = o3.snapshot.clone() {
                let w = w.clone();
                glib::timeout_add_local_once(std::time::Duration::from_millis(o3.snapshot_delay), move || {
                    script::snapshot(&w, &path);
                    if let Some(app) = w.application() {
                        app.quit();
                    }
                });
            }
            if let Some(s) = o3.script.clone() {
                script::run(&a2, &s);
            }
            glib::ControlFlow::Break
        });
        win.present();
        *st.borrow_mut() = Some(a);
    });
    let st = state.clone();
    gapp.connect_open(move |gapp, files, _| {
        gapp.activate();
        for f in files {
            let uri = f.uri();
            let a = st.borrow().clone();
            if let (Some(a), Some(addr)) = (a, godnotic_core::link::parse_join(&uri)) {
                session_ui::confirm_join(&a, addr);
            }
        }
    });
    if let Some(l) = &o.link {
        eprintln!("godnotic-loader: link {l}");
    }
    // GApplication would parse our options; they are already read.
    gapp.run_with_args(&["godnotic-loader"])
}

/// First use starts with the data download, before the game home screen.
fn show_first_download(a: &app::App, use_engine: bool) {
    let Some(nav) = a.ui.borrow().as_ref().map(|u| u.nav.clone()) else { return };
    let weak = Rc::downgrade(a);
    let started = Rc::new(std::cell::Cell::new(false));
    let once = started.clone();
    let done: Rc<dyn Fn()> = Rc::new(move || {
        if !once.replace(true) {
            if let Some(a) = weak.upgrade() { start_ui(&a, use_engine); }
        }
    });
    let check = done.clone();
    glib::timeout_add_local(std::time::Duration::from_millis(750), move || {
        if started.get() { return glib::ControlFlow::Break; }
        if data_ui::ready() { check(); return glib::ControlFlow::Break; }
        glib::ControlFlow::Continue
    });
    let controls = data_ui::content(Some(done));
    let clamp = adw::Clamp::builder().maximum_size(520).child(&controls).build();
    let page = adw::StatusPage::builder()
        .icon_name("folder-download-symbolic")
        .title("Download game data")
        .description("Download the game data to start playing.")
        .child(&clamp).build();
    let scroll = gtk::ScrolledWindow::builder().vexpand(true).child(&page).build();
    let first = adw::NavigationPage::builder().tag("first-download").title("Game data").child(&scroll).can_pop(false).build();
    nav.replace(&[first]);
}

/// The engine process ended. What it described (a pause page, a match) is gone with it:
/// never left on screen. A clean exit is the player quitting (Quit, or closing the game's
/// own window when the two windows take turns): the app quits with it, so the next start
/// is a fresh one at Home. A crash shows that the game stopped, with Start again (a new
/// engine, at Home).
fn engine_exited(a: &app::App) -> bool {
    let status = match &mut *a.backend.borrow_mut() {
        Backend::Engine(e) => match e.proc.child.try_wait() {
            Ok(Some(s)) => s,
            _ => return false,
        },
        _ => return true,
    };
    eprintln!("godnotic-loader: the engine exited ({status})");
    forget_engine_ui(a);
    if status.success() {
        if let Some(app) = a.ui.borrow().as_ref().and_then(|u| u.win.application()) {
            app.quit();
        }
        return true;
    }
    let why = match status.code() {
        Some(c) => format!("The game stopped (exit code {c})."),
        None => "The game stopped unexpectedly.".to_string(),
    };
    *a.backend.borrow_mut() = Backend::Offline(why);
    show_stopped(a);
    true
}

/// Drops every page, dialog and node the engine described.
fn forget_engine_ui(a: &app::App) {
    *a.core.borrow_mut() = app::Core { store: Default::default(), client: Default::default() };
    a.out.borrow_mut().clear();
    a.deferred.borrow_mut().clear();
    let dialogs: Vec<adw::Dialog> = a.ui.borrow_mut().as_mut().map(|u| {
        u.pages.clear();
        u.shown_stack.clear();
        u.updaters.clear();
        u.activators.clear();
        u.lists.clear();
        u.dialogs.drain().map(|(_, d)| d).collect()
    }).unwrap_or_default();
    for d in dialogs {
        d.force_close();
    }
}

/// The engine crashed: say so, with Start again (a new engine, which opens at Home).
fn show_stopped(a: &app::App) {
    let why = match &*a.backend.borrow() {
        Backend::Offline(w) => w.clone(),
        _ => return,
    };
    let Some((nav, win)) = a.ui.borrow().as_ref().map(|u| (u.nav.clone(), u.win.clone())) else { return };
    let s = adw::StatusPage::builder().icon_name("input-gaming-symbolic").title("Godnotic").description(glib::markup_escape_text(&why)).build();
    let again = gtk::Button::with_label("Start again");
    again.add_css_class("gn-play");
    again.set_halign(gtk::Align::Center);
    let w = Rc::downgrade(a);
    again.connect_clicked(move |_| {
        if let Some(a) = w.upgrade() {
            start_ui(&a, true);
        }
    });
    s.set_child(Some(&again));
    let tv = adw::ToolbarView::new();
    tv.add_top_bar(&adw::HeaderBar::new());
    tv.set_content(Some(&s));
    nav.replace(&[adw::NavigationPage::builder().tag("stopped").title("Godnotic").child(&tv).can_pop(false).build()]);
    if !win.is_visible() {
        win.present();
    }
}

/// Says hello to the backend; the snapshot arrives through the pump.
fn start_ui(a: &app::App, use_engine: bool) {
    if use_engine {
        let embed = embed_parent(a);
        match session_ui::start_backend(embed) {
            Ok(proc) => {
                *a.backend.borrow_mut() = Backend::Engine(backend::EngineBackend { proc, embedded: embed.is_some() });
                if std::env::var_os("GODNOTIC_TIMING").is_some() || embed.is_some() {
                    eprintln!("godnotic-loader: engine started{}", embed.map(|x| format!(", its window inside this one (0x{x:x})")).unwrap_or_default());
                }
                watch_window(a);
                let weak = Rc::downgrade(a);
                glib::timeout_add_local(std::time::Duration::from_millis(80), move || {
                    if let Some(a) = weak.upgrade() {
                        app::schedule(&a);
                        if engine_exited(&a) {
                            return glib::ControlFlow::Break;
                        }
                        glib::ControlFlow::Continue
                    } else { glib::ControlFlow::Break }
                });
            }
            Err(e) => { *a.backend.borrow_mut() = Backend::Offline(e.to_string()); }
        }
    }
    if matches!(&*a.backend.borrow(), Backend::Offline(_)) {
        show_offline(a);
        return;
    }
    let caps = caps(a);
    let m = a.core.borrow_mut().client.hello("godnotic-gtk", env!("CARGO_PKG_VERSION"), caps);
    app::send(a, m);
    backend::run_steps(a);
}

/// Section 7's capabilities from the window and the system.
fn caps(a: &app::App) -> serde_json::Value {
    let (w, h) = a.ui.borrow().as_ref().map(|u| (u.win.width().max(u.win.default_width()), u.win.height().max(u.win.default_height()))).unwrap_or((1100, 760));
    let class = |v: i32, a: i32, b: i32| if v < a { "compact" } else if v < b { "medium" } else { "expanded" };
    let env: std::collections::HashMap<String, String> = std::env::vars().collect();
    let p = godnotic_core::platform::Platform::detect(&env, &|f| std::fs::read_to_string(f).ok(), &|d| std::fs::read_dir(d).map(|r| r.filter_map(|e| e.ok()?.file_name().into_string().ok()).collect()).unwrap_or_default());
    json!({
        "nodes": godnotic_core::bridge::NODE_TYPES, "hints": godnotic_core::bridge::HINTS,
        "width": w, "height": h, "widthClass": class(w, 600, 840), "heightClass": class(h, 480, 900),
        "input": if p.form == godnotic_core::platform::FormFactor::Phone { json!(["touch"]) } else { json!(["mouse", "keyboard"]) },
        "locale": std::env::var("LANG").ok().and_then(|l| l.split(['.', '_']).next().map(str::to_string)).unwrap_or_else(|| "en".into()),
        "reducedMotion": a.reduced_motion.get(), "powerSave": p.saving_power.unwrap_or(false), "thermal": "nominal",
        "insets": {"top": 0, "right": 0, "bottom": 0, "left": 0},
        "focused": window_state(a).0, "visible": window_state(a).1,
    })
}

/// The window's focus and visibility (shown, not minimised), for the engine: it mutes and
/// stops drawing by them while its own window is not a top-level of its own.
fn window_state(a: &app::App) -> (bool, bool) {
    let Some(win) = a.ui.borrow().as_ref().map(|u| u.win.clone()) else { return (true, true) };
    let minimised = win.surface().and_downcast::<gdk::Toplevel>().is_some_and(|t| t.state().contains(gdk::ToplevelState::MINIMIZED));
    let visible = win.is_visible() && !minimised;
    (visible && win.is_active(), visible)
}

/// Tells the engine (caps) when the window gains or loses focus, hides, or is minimised.
fn watch_window(a: &app::App) {
    let Some(win) = a.ui.borrow().as_ref().map(|u| u.win.clone()) else { return };
    let last = Rc::new(std::cell::Cell::new(window_state(a)));
    let w = Rc::downgrade(a);
    let changed: Rc<dyn Fn()> = Rc::new(move || {
        let Some(a) = w.upgrade() else { return };
        let now = window_state(&a);
        if last.replace(now) != now {
            let c = caps(&a);
            let m = a.core.borrow_mut().client.caps(c);
            app::send(&a, m);
        }
    });
    let c = changed.clone();
    win.connect_is_active_notify(move |_| c());
    let c = changed.clone();
    win.connect_visible_notify(move |_| c());
    if let Some(t) = win.surface().and_downcast::<gdk::Toplevel>() {
        let c = changed.clone();
        t.connect_state_notify(move |_| c());
    }
}

/// One window on Linux: the game's window goes inside this one, which needs X11 (an X11
/// session, or XWayland). On a Wayland session with XWayland the app asks GTK for X11
/// before it starts. GODNOTIC_EMBED=0 keeps the two windows (they then take turns).
fn embed_wanted() -> bool {
    std::env::var("GODNOTIC_EMBED").map(|v| v != "0").unwrap_or(true)
}

fn embed_backend_choice(o: &Opts) {
    let real_engine = o.fixture.is_none() && o.snapshot.is_none() && (o.script.is_none() || std::env::var_os("GODNOTIC_TEST_NATIVE_BACKEND").is_some());
    let has_x = std::env::var_os("DISPLAY").is_some_and(|d| !d.is_empty());
    if real_engine && embed_wanted() && has_x && std::env::var_os("GDK_BACKEND").is_none() {
        std::env::set_var("GDK_BACKEND", "x11");
    }
}

extern "C" {
    // libgtk-4 (built with its X11 backend): the X11 window behind a GdkSurface
    fn gdk_x11_surface_get_xid(surface: *mut gdk::ffi::GdkSurface) -> std::os::raw::c_ulong;
}

/// This window's X11 id, when the game's window can go inside it.
fn embed_parent(a: &app::App) -> Option<u64> {
    use gtk::glib::translate::ToGlibPtr;
    if !embed_wanted() {
        return None;
    }
    let win = a.ui.borrow().as_ref().map(|u| u.win.clone())?;
    let surface = win.surface()?;
    if surface.display().type_().name() != "GdkX11Display" {
        return None;
    }
    // SAFETY: the surface is an X11 surface (checked above) and alive while `surface` is held.
    let xid = unsafe { gdk_x11_surface_get_xid(surface.to_glib_none().0) };
    (xid != 0).then_some(xid as u64)
}

/// No engine UI: the skeleton gives way to a status page.
fn show_offline(a: &app::App) {
    let why = match &*a.backend.borrow() {
        Backend::Offline(w) => w.clone(),
        _ => return,
    };
    let Some(nav) = a.ui.borrow().as_ref().map(|u| u.nav.clone()) else { return };
    let tv = adw::ToolbarView::new();
    let hb = adw::HeaderBar::new();
    tv.add_top_bar(&hb);
    let s = adw::StatusPage::builder().icon_name("input-gaming-symbolic").title("Godnotic").description(glib::markup_escape_text(&why)).build();
    let play = gtk::Button::with_label("Play");
    play.add_css_class("gn-play");
    play.set_halign(gtk::Align::Center);
    let w = Rc::downgrade(a);
    play.connect_clicked(move |_| {
        if let Some(a) = w.upgrade() {
            session_ui::start(&a, godnotic_core::session::Action::Play);
        }
    });
    s.set_child(Some(&play));
    tv.set_content(Some(&s));
    let page = adw::NavigationPage::builder().tag("offline").title("Godnotic").child(&tv).can_pop(false).build();
    nav.replace(&[page]);
}
