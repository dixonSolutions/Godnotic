// SPDX-License-Identifier: GPL-3.0-or-later
//! Flatpak self-update as Settings > Updates shows it.
//!
//! Inside the sandbox the app cannot run `flatpak update`; the Flatpak portal
//! (`org.freedesktop.portal.Flatpak`, Flatpak 1.6 and later) does it for it:
//! an update monitor reports the running, installed and remote commits
//! (`UpdateAvailable`), `Update` installs the newest build with `Progress`
//! signals, and `Spawn` with the latest-version flag starts the new build.
//! [`crate::portal`] speaks that D-Bus; this is the state it drives, kept free
//! of D-Bus so it is tested on its own, and the `update-status.txt` it writes
//! for the menu (format in qcsrc/menu/xonotic/touch_update_util.qh).
//!
//! The portal polls only every 30 minutes, and never on a metered network, so
//! Check now is the app's own: the remote's `summary` (a few KB) names the
//! commit of this app's ref, and the release changelog published next to it
//! names the version.

/// Where the Flatpak remote lives (scripts/publish-flatpak-remote.sh).
pub const REMOTE_URL: &str = "https://dixonsolutions.github.io/Godnotic/flatpak";
/// What changed in each release, newest first; deployed with the remote.
pub const CHANGELOG_URL: &str = "https://dixonsolutions.github.io/Godnotic/changelog.txt";

/// The portal's `Progress` status values.
pub mod status {
    pub const RUNNING: u32 = 0;
    /// Nothing to update: the installation already has the remote's commit.
    pub const EMPTY: u32 = 1;
    pub const DONE: u32 = 2;
    pub const ERROR: u32 = 3;
}

/// One `Progress` signal.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Progress {
    pub op: u32,
    pub n_ops: u32,
    /// 0-100 within the current operation.
    pub progress: u32,
    pub status: u32,
    pub error: String,
    pub error_message: String,
}

impl Progress {
    /// The whole update, 0-100: operations done plus the share of the current one.
    pub fn percent(&self) -> u32 {
        if self.n_ops == 0 {
            return self.progress.min(100);
        }
        ((self.op.min(self.n_ops) * 100 + self.progress.min(100)) / self.n_ops).min(100)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Phase {
    /// No portal to update through (Flatpak older than 1.6): the package manager updates, as before.
    Unavailable(String),
    /// Nothing checked yet.
    Idle,
    Checking,
    UpToDate,
    Available,
    Updating(Progress),
    /// The new build is installed; this process is still the old one until it restarts.
    Restart,
    Error(String),
}

/// What the menu asks for (update-request.txt).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Request {
    Check,
    Install,
    Restart,
}

impl Request {
    pub fn parse(text: &str) -> Option<Self> {
        match text.lines().next()?.trim() {
            "check" => Some(Self::Check),
            // "download" is the Android token for the same thing; a Flatpak update is one step
            "install" | "download" => Some(Self::Install),
            "restart" => Some(Self::Restart),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Updates {
    /// The running build's version ("" when the build does not say).
    pub installed: String,
    /// The newest version the remote has, when known.
    pub latest: String,
    pub phase: Phase,
    pub running_commit: String,
    pub local_commit: String,
    pub remote_commit: String,
    /// Unix seconds when the last check ended, and how.
    pub checked_at: Option<u64>,
    pub checked_result: &'static str,
}

impl Updates {
    pub fn new(installed: &str, running_commit: &str) -> Self {
        Self {
            installed: installed.trim().to_string(),
            latest: String::new(),
            phase: Phase::Idle,
            running_commit: running_commit.to_string(),
            local_commit: String::new(),
            remote_commit: String::new(),
            checked_at: None,
            checked_result: "",
        }
    }

    fn busy(&self) -> bool {
        matches!(self.phase, Phase::Updating(_) | Phase::Restart)
    }

    pub fn portal_unavailable(&mut self, why: &str) {
        self.phase = Phase::Unavailable(why.to_string());
    }

    pub fn available_through_portal(&self) -> bool {
        !matches!(self.phase, Phase::Unavailable(_))
    }

    /// Check now. False when there is nothing to check (no portal, or an update is under way).
    pub fn check_started(&mut self) -> bool {
        if self.busy() || !self.available_through_portal() {
            return false;
        }
        self.phase = Phase::Checking;
        true
    }

    /// Our own check: the remote's commit for this app, and the newest version it names.
    pub fn check_done(&mut self, now: u64, remote_commit: &str, newest: Option<&str>) {
        self.remote_commit = remote_commit.to_string();
        if let Some(v) = newest.map(str::trim).filter(|v| !v.is_empty()) {
            self.latest = v.to_string();
        }
        self.settle(now);
    }

    pub fn check_failed(&mut self, now: u64, why: &str) {
        self.checked_at = Some(now);
        self.checked_result = "error";
        if !self.busy() && self.available_through_portal() {
            self.phase = Phase::Error(why.to_string());
        }
    }

    /// The portal's `UpdateAvailable`: it fires on its own poll, and when the
    /// installation changes under the running app (a `flatpak update` elsewhere).
    pub fn update_available(&mut self, now: u64, running: &str, local: &str, remote: &str) {
        if !running.is_empty() {
            self.running_commit = running.to_string();
        }
        self.local_commit = local.to_string();
        self.remote_commit = remote.to_string();
        if matches!(self.phase, Phase::Updating(_)) {
            return;
        }
        self.settle(now);
    }

    /// Up to date, an update to install, or one installed and waiting for a restart.
    /// A restart that is due stays due: only starting the new build ends it.
    fn settle(&mut self, now: u64) {
        self.checked_at = Some(now);
        if self.phase == Phase::Restart {
            self.checked_result = "available";
            return;
        }
        let local = if self.local_commit.is_empty() { self.running_commit.clone() } else { self.local_commit.clone() };
        let remote = if self.remote_commit.is_empty() { local.clone() } else { self.remote_commit.clone() };
        if remote != local {
            self.phase = Phase::Available;
            self.checked_result = "available";
            if self.latest == self.installed {
                // the changelog has not caught up with the remote (CDN caches): no number
                self.latest.clear();
            }
        } else if local != self.running_commit {
            self.phase = Phase::Restart;
            self.checked_result = "available";
        } else {
            self.phase = Phase::UpToDate;
            self.checked_result = "uptodate";
            self.latest = self.installed.clone();
        }
    }

    /// Update now was sent. False when one is running already.
    pub fn update_started(&mut self) -> bool {
        if self.busy() || !self.available_through_portal() {
            return false;
        }
        self.phase = Phase::Updating(Progress::default());
        true
    }

    /// The portal refused the `Update` call itself.
    pub fn update_failed(&mut self, why: &str) {
        self.phase = Phase::Error(why.to_string());
    }

    pub fn progress(&mut self, now: u64, p: Progress) {
        match p.status {
            status::RUNNING => self.phase = Phase::Updating(p),
            status::EMPTY => {
                // nothing newer on this install's remote
                self.remote_commit = self.local_commit.clone();
                if self.local_commit.is_empty() || self.local_commit == self.running_commit {
                    self.phase = Phase::UpToDate;
                    self.latest = self.installed.clone();
                    self.checked_result = "uptodate";
                } else {
                    self.phase = Phase::Restart;
                }
                self.checked_at = Some(now);
            }
            status::DONE => {
                if !self.remote_commit.is_empty() {
                    self.local_commit = self.remote_commit.clone();
                }
                self.phase = Phase::Restart;
            }
            _ => {
                let why = if !p.error_message.is_empty() {
                    p.error_message.clone()
                } else if !p.error.is_empty() {
                    p.error.clone()
                } else {
                    "The update failed".to_string()
                };
                self.phase = Phase::Error(why);
            }
        }
    }

    pub fn state_name(&self) -> &'static str {
        match self.phase {
            Phase::Unavailable(_) => "managed",
            Phase::Idle => "idle",
            Phase::Checking => "checking",
            Phase::UpToDate => "uptodate",
            Phase::Available => "available",
            Phase::Updating(_) => "downloading",
            Phase::Restart => "ready",
            Phase::Error(_) => "error",
        }
    }

    pub fn message(&self) -> String {
        let v = |s: &str| if s.is_empty() { String::new() } else { format!(" {s}") };
        match &self.phase {
            Phase::Unavailable(why) => why.clone(),
            Phase::Idle => "Not checked yet".into(),
            Phase::Checking => "Checking for updates...".into(),
            Phase::UpToDate => "Up to date".into(),
            Phase::Available => format!("Version{} is available", v(&self.latest)),
            Phase::Updating(p) => format!("Updating: {}%", p.percent()),
            Phase::Restart => "Restart Godnotic to finish the update".into(),
            Phase::Error(why) => why.clone(),
        }
    }

    /// update-status.txt (touch_update_util.qh). `now` in Unix seconds.
    pub fn status_text(&self, now: u64) -> String {
        let one = |s: &str| s.replace(['\n', '\r'], " ");
        let behind = u8::from(matches!(self.phase, Phase::Available | Phase::Restart | Phase::Updating(_)));
        let percent = if let Phase::Updating(p) = &self.phase { p.percent() } else { 0 };
        let mut t = format!(
            "1\n{}\n{}\n{}\n{}\n{}\n{}\noff\nvia=flatpak\nportal={}\n",
            self.state_name(),
            one(&self.installed),
            one(&self.latest),
            behind,
            percent,
            one(&self.message()),
            if self.available_through_portal() { "yes" } else { "none" },
        );
        if let Phase::Updating(p) = &self.phase {
            if p.n_ops > 1 {
                t.push_str(&format!("step={}\nsteps={}\n", (p.op + 1).min(p.n_ops), p.n_ops));
            }
        }
        if let Some(at) = self.checked_at {
            t.push_str(&format!("checked_min={}\nchecked_result={}\n", now.saturating_sub(at) / 60, self.checked_result));
        }
        if !self.running_commit.is_empty() {
            t.push_str(&format!("commit={}\n", short(&self.running_commit)));
        }
        if !self.remote_commit.is_empty() {
            t.push_str(&format!("remote_commit={}\n", short(&self.remote_commit)));
        }
        t
    }
}

fn short(commit: &str) -> &str {
    commit.get(..12).unwrap_or(commit)
}

/// The running instance, from `/.flatpak-info`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Instance {
    pub app_id: String,
    pub commit: String,
    pub arch: String,
    pub branch: String,
    pub flatpak_version: String,
}

impl Instance {
    pub fn parse(keyfile: &str) -> Option<Self> {
        let mut group = "";
        let mut i = Self::default();
        for line in keyfile.lines() {
            let line = line.trim();
            if let Some(g) = line.strip_prefix('[').and_then(|l| l.strip_suffix(']')) {
                group = match g {
                    "Application" => "a",
                    "Instance" => "i",
                    _ => "",
                };
                continue;
            }
            let Some((k, v)) = line.split_once('=') else { continue };
            match (group, k.trim()) {
                ("a", "name") => i.app_id = v.trim().into(),
                ("i", "app-commit") => i.commit = v.trim().into(),
                ("i", "arch") => i.arch = v.trim().into(),
                ("i", "branch") => i.branch = v.trim().into(),
                ("i", "flatpak-version") => i.flatpak_version = v.trim().into(),
                _ => {}
            }
        }
        (!i.app_id.is_empty()).then_some(i)
    }

    /// `app/<id>/<arch>/<branch>`, the ref the remote's summary lists.
    pub fn app_ref(&self) -> String {
        format!("app/{}/{}/{}", self.app_id, self.arch, if self.branch.is_empty() { "master" } else { &self.branch })
    }
}

/// The newest version in the release changelog: its first line that is not an entry.
pub fn newest_in_changelog(text: &str) -> Option<String> {
    let first = text.lines().map(str::trim).find(|l| !l.is_empty())?;
    let ok = !first.starts_with('-') && first.len() <= 32 && first.chars().next()?.is_ascii_digit()
        && first.chars().all(|c| c.is_ascii_alphanumeric() || ".-+_".contains(c));
    ok.then(|| first.to_string())
}

/// The version CI stamped into the AppStream metainfo (scripts/set-package-version.sh
/// writes "Automated CI build <version>." as the newest release). Flatpak builds
/// before version.txt was stamped have only this. A local build's metainfo has
/// only the hand-written releases, which are not its version: None.
pub fn version_from_metainfo(xml: &str) -> Option<String> {
    const MARK: &str = "Automated CI build ";
    let rest = &xml[xml.find(MARK)? + MARK.len()..];
    let v = rest[..rest.find('<')?].trim().trim_end_matches('.');
    (!v.is_empty() && !v.contains(char::is_whitespace)).then(|| v.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const OLD: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const NEW: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn lines(t: &str) -> Vec<&str> {
        t.lines().collect()
    }

    #[test]
    fn a_check_finds_an_update_installs_it_and_asks_for_a_restart() {
        let mut u = Updates::new("1.2.205", OLD);
        assert_eq!(u.state_name(), "idle");
        assert!(u.check_started());
        assert_eq!(u.state_name(), "checking");
        u.check_done(1000, NEW, Some("1.2.206"));
        assert_eq!(u.phase, Phase::Available);
        let t = u.status_text(1000 + 125);
        let l = lines(&t);
        assert_eq!(&l[..9], &["1", "available", "1.2.205", "1.2.206", "1", "0", "Version 1.2.206 is available", "off", "via=flatpak"]);
        assert!(l.contains(&"portal=yes") && l.contains(&"checked_min=2") && l.contains(&"checked_result=available"));
        assert!(u.update_started());
        assert!(!u.update_started(), "one update at a time");
        assert!(!u.check_started(), "no check while updating");
        u.progress(1001, Progress { op: 0, n_ops: 2, progress: 50, ..Default::default() });
        assert_eq!(u.state_name(), "downloading");
        let t = u.status_text(1001);
        assert_eq!(lines(&t)[5], "25");
        assert!(t.contains("step=1\nsteps=2\n"));
        u.progress(1002, Progress { op: 1, n_ops: 2, progress: 99, ..Default::default() });
        assert_eq!(lines(&u.status_text(1002))[5], "99");
        u.progress(1003, Progress { op: 1, n_ops: 2, progress: 100, status: status::DONE, ..Default::default() });
        assert_eq!(u.phase, Phase::Restart);
        assert_eq!(lines(&u.status_text(1003))[1], "ready");
        assert_eq!(lines(&u.status_text(1003))[3], "1.2.206");
        // a portal poll after the install keeps asking for the restart
        u.update_available(1004, OLD, NEW, NEW);
        assert_eq!(u.phase, Phase::Restart);
    }

    #[test]
    fn up_to_date_checks_and_portal_signals() {
        let mut u = Updates::new("1.2.206", NEW);
        u.check_started();
        u.check_done(50, NEW, Some("1.2.206"));
        assert_eq!(u.phase, Phase::UpToDate);
        let t = u.status_text(50);
        assert_eq!(&lines(&t)[1..5], &["uptodate", "1.2.206", "1.2.206", "0"]);
        assert!(t.contains("checked_min=0\nchecked_result=uptodate\n"));
        // the portal's first poll always reports, equal commits: still up to date
        u.update_available(60, NEW, NEW, NEW);
        assert_eq!(u.phase, Phase::UpToDate);
        // then the remote moves on
        u.update_available(70, NEW, NEW, OLD);
        assert_eq!(u.phase, Phase::Available);
        assert_eq!(u.latest, "", "the version is unknown until the changelog names one");
    }

    #[test]
    fn an_update_installed_elsewhere_needs_only_a_restart() {
        // `flatpak update` in a terminal while the game runs
        let mut u = Updates::new("1.2.205", OLD);
        u.update_available(10, OLD, NEW, NEW);
        assert_eq!(u.phase, Phase::Restart);
        // and Update now on it finds nothing to do, which is not an error
        let mut v = Updates::new("1.2.205", OLD);
        v.local_commit = NEW.into();
        v.update_started();
        v.progress(11, Progress { status: status::EMPTY, ..Default::default() });
        assert_eq!(v.phase, Phase::Restart);
    }

    #[test]
    fn nothing_to_update_means_up_to_date() {
        let mut u = Updates::new("1.2.206", NEW);
        u.check_done(5, OLD, None); // a remote that disagrees with the install's own origin
        assert_eq!(u.phase, Phase::Available);
        u.update_started();
        u.progress(6, Progress { status: status::EMPTY, ..Default::default() });
        assert_eq!(u.phase, Phase::UpToDate);
        assert_eq!(u.latest, "1.2.206");
    }

    #[test]
    fn errors_say_why() {
        let mut u = Updates::new("1.2.205", OLD);
        u.update_started();
        u.progress(1, Progress { status: status::ERROR, error: "org.freedesktop.DBus.Error.NotSupported".into(),
            error_message: "Self update not supported, new version requires new permissions".into(), ..Default::default() });
        assert_eq!(u.state_name(), "error");
        assert_eq!(lines(&u.status_text(1))[6], "Self update not supported, new version requires new permissions");
        assert!(u.check_started(), "Check now works again after an error");
        u.check_failed(2, "Network error: no route");
        let t = u.status_text(2);
        assert_eq!(lines(&t)[6], "Network error: no route");
        assert!(t.contains("checked_result=error"));
        u.update_failed("Application update not allowed");
        assert_eq!(lines(&u.status_text(3))[6], "Application update not allowed");
    }

    #[test]
    fn without_the_portal_the_package_manager_updates() {
        let mut u = Updates::new("1.2.205", OLD);
        u.portal_unavailable("Updating from here needs Flatpak 1.6 or newer");
        assert!(!u.check_started() && !u.update_started());
        let t = u.status_text(0);
        assert_eq!(&lines(&t)[..10], &["1", "managed", "1.2.205", "", "0", "0", "Updating from here needs Flatpak 1.6 or newer", "off", "via=flatpak", "portal=none"]);
    }

    #[test]
    fn requests_progress_and_parsing() {
        assert_eq!(Request::parse("check\n"), Some(Request::Check));
        assert_eq!(Request::parse("install"), Some(Request::Install));
        assert_eq!(Request::parse("download\n"), Some(Request::Install));
        assert_eq!(Request::parse("restart\n"), Some(Request::Restart));
        assert_eq!(Request::parse("auto-on\n"), None);
        assert_eq!(Progress { op: 0, n_ops: 1, progress: 40, ..Default::default() }.percent(), 40);
        assert_eq!(Progress { op: 3, n_ops: 3, progress: 100, ..Default::default() }.percent(), 100);
        assert_eq!(Progress::default().percent(), 0);

        let info = "[Application]\nname=io.github.dixonSolutions.Godnotic\nruntime=runtime/org.gnome.Platform/x86_64/48\n\n[Instance]\ninstance-id=1\napp-commit=03c3d5\nbranch=master\narch=x86_64\nflatpak-version=1.18.4\n";
        let i = Instance::parse(info).unwrap();
        assert_eq!((i.commit.as_str(), i.flatpak_version.as_str()), ("03c3d5", "1.18.4"));
        assert_eq!(i.app_ref(), "app/io.github.dixonSolutions.Godnotic/x86_64/master");
        assert_eq!(Instance::parse("[Instance]\napp-commit=1\n"), None);

        assert_eq!(newest_in_changelog("\n1.2.206\n- Use the native Linux interface\n\n1.2.205\n- x\n").as_deref(), Some("1.2.206"));
        assert_eq!(newest_in_changelog("<html>404</html>"), None);
        assert_eq!(newest_in_changelog(""), None);

        let stamped = "<releases>\n    <release version=\"1.2.206\" date=\"2026-11-15\">\n      <description>\n        <p>Automated CI build 1.2.206.</p>\n      </description>\n    </release>\n    <release version=\"1.1.1\" date=\"2026-06-21\">\n      <description>\n        <p>Flatpak-first distribution.</p>\n      </description>\n    </release>\n  </releases>";
        assert_eq!(version_from_metainfo(stamped).as_deref(), Some("1.2.206"));
        let local = "<releases>\n    <release version=\"1.1.1\" date=\"2026-06-21\">\n      <description>\n        <p>Flatpak-first distribution.</p>\n      </description>\n    </release>\n  </releases>";
        assert_eq!(version_from_metainfo(local), None, "a local build is not 1.1.1");
    }
}
