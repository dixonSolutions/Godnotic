// SPDX-License-Identifier: GPL-3.0-or-later
//! Starting the game from the app and coming back when the match ends.
//!
//! Until the engine's UI-bridge backend mode lands (work/uibridge), the game
//! is started through packaging/start.sh, in native front end mode for a join
//! (engine/darkplaces/touch_nativefe.c). The app window hides while the game
//! runs (one window on screen), the engine quits by itself if the app goes
//! (GODNOTIC_FE_WATCH_PID), and the app reads how the session ended: a kick,
//! a refused join (asks for the password again), a crash (with the log path).

use crate::app::App;
use adw::prelude::*;
use godnotic_core::config::Config;
use godnotic_core::desktop::{self, JoinRoute};
use godnotic_core::link::ServerAddress;
use godnotic_core::paths::GamePaths;
use godnotic_core::session::{self, Action, Outcome};
use gtk::{gio, glib};
use std::collections::HashMap;
use std::path::Path;
use std::rc::Rc;

fn paths() -> (GamePaths, Config) {
    let env: HashMap<String, String> = std::env::vars().collect();
    let exe = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_string_lossy().to_string()));
    let cfg = std::fs::read_to_string(GamePaths::config_file(&env)).map(|t| Config::parse(&t)).unwrap_or_default();
    let p = GamePaths::resolve(&env, exe.as_deref(), &|f| Path::new(f).exists(), cfg.get(godnotic_core::config::keys::USER_BASE).unwrap_or(""));
    (p, cfg)
}

/// Start the engine as the native UI backend for the lifetime of the app.
pub fn start_backend() -> std::io::Result<session::Backend> {
    let (p, cfg) = paths();
    let start = p.start_script.ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "The game was not found next to the app"))?;
    let (env, args) = session::base_args(&cfg);
    let mut command = std::process::Command::new(start);
    command.args(args);
    command.envs(env);
    command.envs(session::window_identity_env());
    session::Backend::start_socket(command)
}

fn toast(app: &App, text: &str) {
    if let Some(u) = app.ui.borrow().as_ref() {
        u.toasts.add_toast(adw::Toast::new(text));
    }
}

/// Joins a server. The engine that is already running (the app's UI backend) is asked over
/// the bridge, as the Multiplayer screen's address field does: a second engine would end at
/// once on the instance lock, or be a second game window. Only with no engine (the offline
/// page, fixtures) is one started with the address.
pub fn join(app: &App, address: ServerAddress, password: Option<String>) {
    match desktop::join_route(crate::backend::engine_live(app)) {
        JoinRoute::Bridge => {
            // The engine asks for the password itself (its own join dialog) when the server wants one.
            *app.pending_join.borrow_mut() = Some(crate::app::PendingJoin { address: address.to_string(), asked: false, since: std::time::Instant::now() });
            advance_join(app);
            let w = std::rc::Rc::downgrade(app);
            glib::timeout_add_local_once(JOIN_WAIT, move || {
                if let Some(a) = w.upgrade() { advance_join(&a); }
            });
        }
        JoinRoute::Spawn => start(app, Action::Connect { address, password }),
    }
}

const JOIN_WAIT: std::time::Duration = std::time::Duration::from_secs(10);

/// Carries a pending join one step further (see desktop::join_step); called when one is asked
/// for and after every description from the engine.
pub fn advance_join(app: &App) {
    let Some((address, asked, since)) = app.pending_join.borrow().as_ref().map(|p| (p.address.clone(), p.asked, p.since)) else { return };
    let (context, has_node) = {
        let core = app.core.borrow();
        (core.store.context(), core.store.node(desktop::JOIN_NODE).is_some())
    };
    if since.elapsed() > JOIN_WAIT {
        app.pending_join.borrow_mut().take();
        toast(app, "Could not open the join screen");
        return;
    }
    match desktop::join_step(&context, has_node, asked) {
        desktop::JoinStep::Submit => {
            app.pending_join.borrow_mut().take();
            let m = app.core.borrow_mut().client.submit(desktop::JOIN_NODE, serde_json::json!(address));
            crate::app::send(app, m);
        }
        desktop::JoinStep::OpenMultiplayer => {
            if let Some(p) = app.pending_join.borrow_mut().as_mut() { p.asked = true; }
            let (node, action) = desktop::OPEN_MULTIPLAYER;
            let m = app.core.borrow_mut().client.action(node, action, None);
            crate::app::send(app, m);
        }
        desktop::JoinStep::Wait => {}
        desktop::JoinStep::Unavailable => {
            app.pending_join.borrow_mut().take();
            eprintln!("godnotic-loader: not joining {address}: a match is running");
        }
    }
}

/// Starts a session; the window hides until it ends.
pub fn start(app: &App, action: Action) {
    if crate::backend::engine_live(app) {
        // One engine at a time: whatever asked for a game, the running one is it.
        eprintln!("godnotic-loader: not starting a second engine for {action:?}");
        return;
    }
    let t0 = std::time::Instant::now();
    let (p, cfg) = paths();
    let Some(start) = p.start_script.clone() else {
        toast(app, "The game was not found next to the app");
        return;
    };
    // No game without its data: the storage button downloads it first.
    let ready = Path::new(&p.user_data()).join(godnotic_core::fetch::datasets::READY_MARKER).exists()
        || p.data_dirs().iter().any(|d| std::fs::read_dir(d).into_iter().flatten().flatten().any(|e| {
            let n = e.file_name().to_string_lossy().to_string();
            n.starts_with("xonotic-") && n.ends_with("-data.pk3")
        }));
    if !ready && std::env::var_os("GODNOTIC_SKIP_DATA_CHECK").is_none() {
        toast(app, "Download the game data first");
        return;
    }
    let _ = std::fs::create_dir_all(&p.user_base);
    let result = format!("{}/session-result.txt", p.user_base);
    let log = format!("{}/last-session.log", p.user_base);
    let _ = std::fs::remove_file(&result);
    let mut plan = session::plan(&action, &cfg, &start, &result);
    plan.env.insert("GODNOTIC_FE_WATCH_PID".into(), std::process::id().to_string());
    let child = match session::spawn(&plan, &log) {
        Ok(c) => {
            if std::env::var_os("GODNOTIC_TIMING").is_some() {
                eprintln!("godnotic-loader: Play to engine process started in {:.2} ms", t0.elapsed().as_secs_f64() * 1000.0);
            }
            c
        }
        Err(e) => {
            toast(app, &format!("Could not start the game: {e}"));
            return;
        }
    };
    let Some(win) = app.ui.borrow().as_ref().map(|u| u.win.clone()) else { return };
    let gapp = win.application();
    let hold = std::cell::RefCell::new(gapp.as_ref().map(|a| a.hold()));
    win.set_visible(false);
    println!("session started: window hidden");
    let pid = glib::Pid(child.id() as i32);
    std::mem::forget(child); // reaped by the child watch below, not by std
    let w = Rc::downgrade(app);
    glib::child_watch_add_local(pid, move |_, status| {
        hold.borrow_mut().take();
        let Some(app) = w.upgrade() else { return };
        let (code, signalled) = if libc::WIFSIGNALED(status) { (128 + libc::WTERMSIG(status), true) } else { (libc::WEXITSTATUS(status), false) };
        let text = std::fs::read_to_string(&result).ok();
        let out = Outcome::parse(text.as_deref(), code, signalled, &log);
        println!("session ended: kind={} exit={} reason={}", out.kind, out.exit_code, out.reason);
        if let Some(u) = app.ui.borrow().as_ref() {
            u.win.present();
        }
        if let Some(m) = out.message() {
            toast(&app, &m);
        }
        if out.wants_password() {
            if let Action::Connect { address, .. } = &action {
                ask_password(&app, address.clone(), out.kind == "password_wrong");
            }
        }
    });
}

/// "Join <address>?" for a godnotic:// link; the password is asked only when the server wants one.
pub fn confirm_join(app: &App, address: ServerAddress) {
    let Some(win) = app.ui.borrow().as_ref().map(|u| u.win.clone()) else { return };
    let d = adw::AlertDialog::new(Some("Join this game?"), Some(&address.to_string()));
    d.add_response("cancel", "Cancel");
    d.add_response("join", "Join");
    d.set_response_appearance("join", adw::ResponseAppearance::Suggested);
    d.set_default_response(Some("join"));
    let w = Rc::downgrade(app);
    d.choose(Some(&win), gio::Cancellable::NONE, move |r| {
        if let (Some(a), "join") = (w.upgrade(), r.as_str()) {
            join(&a, address, None);
        }
    });
}

fn ask_password(app: &App, address: ServerAddress, wrong: bool) {
    let Some(win) = app.ui.borrow().as_ref().map(|u| u.win.clone()) else { return };
    let d = adw::AlertDialog::new(Some(if wrong { "Wrong password" } else { "This game needs a password" }), Some(&address.to_string()));
    let row = adw::PasswordEntryRow::builder().title("Password").build();
    row.add_prefix(&gtk::Image::from_icon_name("changes-prevent-symbolic"));
    let g = adw::PreferencesGroup::new();
    g.add(&row);
    d.set_extra_child(Some(&g));
    d.add_response("cancel", "Cancel");
    d.add_response("join", "Join");
    d.set_response_appearance("join", adw::ResponseAppearance::Suggested);
    d.set_default_response(Some("join"));
    let w = Rc::downgrade(app);
    let r2 = row.clone();
    d.choose(Some(&win), gio::Cancellable::NONE, move |r| {
        let pw = r2.text().to_string();
        r2.set_text("");
        if let (Some(a), "join") = (w.upgrade(), r.as_str()) {
            start(&a, Action::Connect { address, password: Some(pw) });
        }
    });
}
