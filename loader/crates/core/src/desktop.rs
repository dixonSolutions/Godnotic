// SPDX-License-Identifier: GPL-3.0-or-later
//! The desktop app's decisions that no toolkit is needed for.
//!
//! On Linux the engine is a separate process that draws the match in its own window while
//! the app draws everything else; on Android the engine lives in the app's process and the
//! app's views are drawn over it. The two had separate answers to the same questions, and
//! Linux's were scattered through the GTK code. They are written once here, with tests, and
//! the GTK app only carries them out:
//!
//! - [`surface`]: which of the two windows owns the screen (exactly one at a time);
//! - [`resume_offered`]: whether a pause page with Resume may be on screen at all;
//! - [`join_route`]: a join (a `godnotic://` link, a typed address) goes to the engine that
//!   is already running, never into a second engine;
//! - [`engine_exit`]: what the app does when the engine process ends.

use std::time::Duration;

/// A clean exit sooner than this after the start is not a quit: the launch script stops at
/// once when another session holds its lock.
pub const EARLY_EXIT: Duration = Duration::from_secs(5);

/// The bridge node a join is submitted on (the Multiplayer screen's address field).
pub const JOIN_NODE: &str = "mp/join/address";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Surface {
    /// The app's window: Home, menus, loading.
    App,
    /// The engine's window: the match.
    Game,
}

/// Which window owns the screen. `context` is the bridge's `app.context` (`menu`, `loading`,
/// `setup` or `game`). Only a live engine can own it: with no engine there is no game to show,
/// whatever the last description said.
pub fn surface(context: &str, engine_live: bool) -> Surface {
    if engine_live && context == "game" {
        Surface::Game
    } else {
        Surface::App
    }
}

/// Resume (the pause page) belongs to a match that is running: the context is `game` and the
/// engine that holds it is alive. After the engine is gone, or once the player has left the
/// match, no page of the old description may stay on screen.
pub fn resume_offered(context: &str, engine_live: bool) -> bool {
    surface(context, engine_live) == Surface::Game
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JoinRoute {
    /// Send the address to the running engine over the UI bridge.
    Bridge,
    /// There is no engine to ask (offline page, fixtures): start the game with the address.
    Spawn,
}

/// A second engine cannot start while the first holds the instance lock (it ends at once, and
/// the player sees nothing happen), and two engines are two game windows. A live engine is
/// always asked over the bridge.
pub fn join_route(engine_live: bool) -> JoinRoute {
    if engine_live {
        JoinRoute::Bridge
    } else {
        JoinRoute::Spawn
    }
}

/// The tile on Home that opens Multiplayer, and the action it carries: the engine only takes a
/// `submit` on [`JOIN_NODE`] while that screen exists ("no such node" otherwise).
pub const OPEN_MULTIPLAYER: (&str, &str) = ("home/tiles/mp", "mp");

/// A join over the bridge, one step at a time.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JoinStep {
    /// The join field exists: submit the address.
    Submit,
    /// Open Multiplayer first (once); its screen appears with the next description.
    OpenMultiplayer,
    /// Multiplayer was asked for and is not there yet.
    Wait,
    /// A match is running: the Multiplayer screen is not reachable from it. The link is dropped
    /// rather than interrupting the match.
    Unavailable,
}

pub fn join_step(context: &str, has_join_node: bool, asked: bool) -> JoinStep {
    if has_join_node {
        JoinStep::Submit
    } else if context == "game" {
        JoinStep::Unavailable
    } else if asked {
        JoinStep::Wait
    } else {
        JoinStep::OpenMultiplayer
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stop {
    /// Ended cleanly within seconds: the launch script found another session running.
    AnotherSession,
    /// Ended by a signal or with an error status.
    Crashed,
}

impl Stop {
    pub fn title(self) -> &'static str {
        match self {
            Stop::AnotherSession => "The game did not start",
            Stop::Crashed => "The game stopped",
        }
    }

    pub fn text(self) -> &'static str {
        match self {
            Stop::AnotherSession => "Another Godnotic session may still be running. Close it, then start again.",
            Stop::Crashed => "Godnotic closed unexpectedly. Start it again to carry on.",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EngineExit {
    /// The player quit the game: so does the app.
    QuitApp,
    /// Keep the app open on a page that offers to start the game again.
    StartAgain(Stop),
}

/// What the app does when the engine process ends. `ran` is how long it ran.
pub fn engine_exit(success: bool, ran: Duration) -> EngineExit {
    match (success, ran < EARLY_EXIT) {
        (true, false) => EngineExit::QuitApp,
        (true, true) => EngineExit::StartAgain(Stop::AnotherSession),
        (false, _) => EngineExit::StartAgain(Stop::Crashed),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_window_owns_the_screen() {
        assert_eq!(surface("game", true), Surface::Game);
        for c in ["menu", "loading", "setup", ""] {
            assert_eq!(surface(c, true), Surface::App, "{c}");
        }
    }

    #[test]
    fn a_dead_engine_owns_nothing() {
        assert_eq!(surface("game", false), Surface::App);
    }

    #[test]
    fn resume_only_while_a_match_is_alive() {
        assert!(resume_offered("game", true));
        assert!(!resume_offered("game", false));
        assert!(!resume_offered("menu", true));
        assert!(!resume_offered("loading", true));
    }

    #[test]
    fn a_live_engine_is_asked_never_duplicated() {
        assert_eq!(join_route(true), JoinRoute::Bridge);
        assert_eq!(join_route(false), JoinRoute::Spawn);
    }

    #[test]
    fn a_join_opens_multiplayer_first_and_never_interrupts_a_match() {
        assert_eq!(join_step("menu", true, false), JoinStep::Submit);
        assert_eq!(join_step("menu", false, false), JoinStep::OpenMultiplayer);
        assert_eq!(join_step("menu", false, true), JoinStep::Wait);
        assert_eq!(join_step("menu", true, true), JoinStep::Submit);
        assert_eq!(join_step("game", false, false), JoinStep::Unavailable);
        assert_eq!(join_step("loading", false, false), JoinStep::OpenMultiplayer);
    }

    #[test]
    fn exits() {
        let s = Duration::from_secs;
        assert_eq!(engine_exit(true, s(60)), EngineExit::QuitApp);
        assert_eq!(engine_exit(true, s(1)), EngineExit::StartAgain(Stop::AnotherSession));
        assert_eq!(engine_exit(false, s(1)), EngineExit::StartAgain(Stop::Crashed));
        assert_eq!(engine_exit(false, s(600)), EngineExit::StartAgain(Stop::Crashed));
        assert_eq!(engine_exit(true, EARLY_EXIT), EngineExit::QuitApp);
    }
}
