# Handoff: `work/flatpakui` (Linux app: Escape, one window, mute, Settings > Controls)

Base: `origin/work/integration4` 60df869, with `origin/main` 4b747d4 ("Use the native Linux
interface and show first-run downloads") merged in first. Clone
`~/.cache/godnotic-work/flatpakui`. Both components changed (`engine/darkplaces`: one new hook
line each in `vid_sdl.c` and `snd_main.c`, `touch_uib_stream.c`, `touch_uib_int.h`,
`touch_hudmode.c`; `engine/data/xonotic-data.pk3dir`: `touch_uib_touchcontrols.qc`), so the
merge needs `scripts/component-push.sh all` and the pins. `component-push.sh` was not run here.
`loader/crates/gtk/src/app.rs` (work/mpfast's lock) is not touched.

## The owner's reports

1. "escape on flatpak pauses the game, no menu, it should pause the game and show the menu."
2. "The game on flatpak still needs a mobile section."
3. "The interface also keeps glitching between new windows for game.." (one window, booted and
   managed by the Rust core).
4. "volume to be fixed, still heard when I am in different window, got this on flatpak".
5. "Do not show paused matches if the user closed the app during one.... restore from home
   screen.." (a screenshot of the GTK Paused page of a local Team Deathmatch).

## Causes

1. **Escape.** In back-end mode (`-uibridge`) `app.context` is `game` for the whole time a
   client is in a match, menu up or not (`UIB_Context`, touch_uib_screens.qc: the stack is
   `pause` from the first frame of a match). The GTK app hid its window whenever the context
   said `game` (`backend.rs` `context_changed`) and the engine showed its own window for
   `game` (`touch_uib_stream.c` `window_policy`). Escape in the match opened the game's menu
   (`key_dest` menu; the local game pauses), but the menu VM does not draw in back-end mode and
   nothing told the app the menu was up: a paused match with no menu. GTK did not swallow the
   key (its window was hidden; its own Escape handler sends `back`, which is right for the
   second Escape). The same hole hid the join and statistics dialogs (`join`, `uid2name`,
   modals in the game context): they opened in the bridge, but no window showed them. Android
   never had it because its Kotlin host reads the front-end bridge's `app` topic (`state`:
   `paused` / `ingame`), which the Linux stream transport does not carry.
2. **The Controls section (the classic menu's "Mobile" tab).** The released Flatpak ran the
   loader's built-in screens (before 4b747d4), whose Settings was the game-data page alone.
   With the engine as back end (4b747d4) Settings is the bridge's, and the touch section is
   there under the same name as on Android, **Controls** ("Presets, HUD, aim assist,
   auto-shoot, tactics"); nothing hides it per platform. What was missing for a Linux PC: the
   switch that turns the on-screen controls on (`vid_touchscreen_mode`, the classic Mobile
   tab's "Input mode"), and any word on when its rows apply on a PC with no touchscreen.
3. **Windows.** Two top-level windows took turns: the GTK window hid and the engine's SDL
   window was shown for a match, and the other way round for the menu; the engine's window was
   also created visible at start and hidden a frame later.
4. **Sound.** The engine mutes by `vid_activewindow` (`snd_mutewhenidle 1`), which follows the
   engine's own SDL window. That window is hidden in the menu and, embedded, is a child that
   does not track the app window's focus; the app never told the engine about its own focus.

5. **The stale Paused page.** In 1.2.206 the GTK window hid itself for a match and the
   engine's SDL window showed it. Closing that window (its close button, the only window on
   screen) ends the engine (`SDL_WINDOWEVENT_CLOSE`, `host_shutdown`), but the loader only
   printed that the engine was gone (`backend.rs` `engine_gone`) and kept running with its
   window hidden: a GApplication quits when its last window is destroyed, not hidden. The
   next launch (a single-instance app) activated that process, whose `connect_activate`
   presented the hidden window as it was: the Paused page of a match that no longer existed,
   over a dead engine. Nothing is restored from disk: no saved snapshot, no `map` or
   `connect` in the configs; a killed app (SIGTERM, SIGKILL) already started fresh.

## What changed

- **`app.menu`** (engine, `touch_uib_stream.c`, back-end mode only): true while the game's
  menu is up (`key_dest` menu or menu_grabbed). In `game` that is the pause menu or a dialog
  over the match. `docs/UI_BRIDGE.md` 4.2, `scripts/uib-schema.py` (schema regenerated). The
  engine's window policy hides the game window while it is true; the app
  (`backend.rs` `match_has_screen`, `context_changed`) shows its pages whenever the match does
  not have the screen. Escape: pause screen; the app's Escape (`back`) resumes (`UIB_Back`
  `m_hide`). Same path for `join` and `uid2name`.
- **One window** (X11 / XWayland). The engine creates its window hidden in back-end mode
  (`vid_sdl.c` hook, `UIBStream_WindowFlags`). The app passes its X11 window id
  (`GODNOTIC_EMBED_PARENT`, `SDL_VIDEODRIVER=x11`, `+vid_fullscreen 0`; `main.rs`
  `embed_parent`, `session_ui.rs` `start_backend`); the engine reparents its window into it
  (libX11 looked up with dlopen; Xlib errors trapped), keeps it at the app window's size (every
  0.25 s), maps it over the GTK pages while the match has the screen, unmaps it for the menu,
  and gives it the keyboard focus when it comes up and when the app window becomes active
  again. The app window never hides in that mode. The child has no background of its own, so
  the app's last frame stays until the game draws: no black flash. The app asks GTK for X11
  before it starts when `DISPLAY` is set (`GODNOTIC_EMBED=0` keeps Wayland and the two
  windows taking turns, which is also what a Wayland-only session gets). The Flatpak manifest
  now has `--socket=x11` instead of `--socket=fallback-x11`.
- **Mute and idle by the app window** (`touch_uib_stream.c`, `vid_sdl.c` and `snd_main.c`
  hooks). The app sends its window state in its caps (`focused`, `visible`: shown and not
  minimised; `main.rs` `watch_window`, on `is-active`, `visible` and the toplevel state).
  Hidden behind the pages: nothing drawn, idle frame rate, and the sound follows the app
  window (menu music plays while the app is active, stops when it is not). Embedded over the
  pages: `vid_activewindow` is the game window's own focus or the app's, so the game mutes
  (`snd_mutewhenidle`, default 1, in Settings > Audio as "Mute when the game is in the
  background") and drops to `cl_maxidlefps` when the app is in the background, and stops
  drawing when it is minimised. No fade (the mute is the engine's `snd_blocked`). Android is
  untouched: every hook returns at once without `-uibridge`.
- **Settings > Controls > Touch controls** (`touch_uib_touchcontrols.qc`): a first section,
  "On-screen controls": "Show the on-screen controls" Automatic / Always / Never
  (`vid_touchscreen_mode`), and on a device with no touchscreen the line "No touchscreen
  found on this device. These settings apply when you play by touch: …". The section name
  stays Controls, as on Android (it is the classic menu's "Mobile" tab).
- **Join dialog on the same map**: `touch_hudmode.c` owed the join dialog once per map name,
  so a server joined on the map of the local game just left never opened it. Now per map and
  server address.
- **Typing an address and pressing Join** (GTK `widgets.rs`): a field with a submit action sent
  nothing until Enter, so Multiplayer's Join stayed off after typing an address. The value now
  goes as it is typed (also on the entry rows with an apply button).
- **Test windows on the user's desktop** (`loader/scripts/run-headless.sh`): from this
  distrobox `flatpak` is host-spawn, flatpak ignores `--env=DISPLAY`, and `--socket=x11` gave
  the sandbox the user's own display `:0`. Every `drive.sh`, `shot.sh`, `test-gtk-screens.sh`
  and `test-gtk-layout.sh` run drew on the owner's desktop. Now the sandbox gets no display
  socket at all and reaches the private Xvfb through its abstract socket (`--share=network`),
  with `DISPLAY` set by `env` inside; the script checks what the sandbox sees before it runs.
  (My own first five live runs of this task went to `:0` too, about two minutes each, before
  I found it.)
- **The app goes with the engine** (`main.rs` `engine_exited`, checked with the pump): when
  the engine process ends, every page, dialog and node it described is dropped; a clean exit
  (Quit, the game's own window closed) quits the app, so the next launch is a new process at
  Home; a crash shows "The game stopped" with Start again (a new engine, at Home).
- Script commands for tests (`script.rs`): `waithidden`, `waitgame`, `waitmenu`, `waittop`,
  `key`, `visible`, `action`, `value`.

## Tests

`scripts/test-gtk-live.sh` (new; needs the loader, the engine, Xvfb, ffmpeg, the GNOME 48
runtime and stock packs in `STOCK`, default `~/.cache/godnotic-infra/repack/src`): the real
app and engine on a private Xvfb through launch, a local match, Escape (a real SDL key event,
`tests/touch-controls/finj.c`), the pause screen, the app's Escape, Leave, Home, Multiplayer,
joining a local server, the join dialog over the match, Auto-select; focus taken away and
given back (`tests/gtk-live/xwin.py`); then Settings > Controls on a PC with no touchscreen.
`tests/gtk-live/check.py` checks the app's record of who shows, the engine's `uib_window` and
`touch_pause_status` at each mark, exactly one top-level window every 50 ms from launch to
quit, no plain black or white frame in the recording, and the Settings labels. It saves
`pause-gtk.png`, `join-gtk.png`, `settings-touch.png`, `match.png` (a frame of the match
inside the app window), `session.mp4` with `OUT=<dir>`.

Run here (this clone, engine and QuakeC built from it; `.suite-base` = repack/src stock packs
plus this clone's pk3dir):

| Command | Result |
| --- | --- |
| `scripts/test-gtk-live.sh` (OUT=…/flatpakui-tools/live10, CPUS=16-19) | all passed: 25 checks (session, focus, Settings, uid2name on the restored 0.8.6 stock data, SIGTERM/SIGKILL/close + relaunch, one window over 666 samples, 322 frames with no flash) |
| `scripts/test-uibridge.sh` | all passed |
| `scripts/test-uibridge.sh --engine` (UIB_BASEDIR=.suite-base) | engine, input, first-run parts passed; the fixture drift check reported every fixture: `app.menu` (now in back-end snapshots) and the new touch rows, which I put in the fixtures, and map mode lists that differ only because repack/src is not the 2023 stock data (left as they were) |
| `cargo test --offline -p godnotic-loader-core` | ok (13 + 11) |
| `loader/build.sh --check` (GNOME SDK 50) | clippy `-D warnings` clean, core tests ok |
| `scripts/test-frontend.sh` | all checks passed |
| `scripts/test-native-menu-live.sh` (STOCKBASE=controls-stock, restored 0.8.6) | all checks passed (68). The joinui case hung twice in `kill %1; wait`: `%1` is not that case's server, which kept running; I stopped the server by hand each time and the case went on (harness bug, not fixed) |
| `scripts/test-gtk-screens.sh`, `scripts/test-gtk-layout.sh` | all passed, now on the private Xvfb |
| `scripts/test-old-name.sh` | PASS |
| SDL engine, `make sv-release`, `scripts/build-web.sh --engine-only` (XT_WEB_DEPS) | all build (the web build first failed on `bool` in touch_uib_int.h; fixed with `int`) |
| CI on `work/flatpakui` | Tests, Loader, Android green on the first commit |

Not run: anything on the tablet or a phone; Android's swipe-away/force-stop (nothing in
this change runs there without `-uibridge`; a killed process starts fresh); a real GNOME
or Phosh session (XWayland embedding, focus from a real window manager, fractional scaling).

## Not done / known

- Wayland-only sessions (no XWayland; Ubuntu Touch's Lomiri) keep two windows taking turns,
  now with the pause menu and the dialogs shown. A true one-window Wayland build needs the
  engine in process (docs/UI_BRIDGE.md 12a).
- On a Wayland desktop the app now runs on XWayland when it can: fractional scaling is the
  compositor's (can be soft on HiDPI). `GODNOTIC_EMBED=0` turns it off. Not checked on a real
  GNOME session or a Linux phone; only on Xvfb with no window manager.
- No 150 ms fade on mute: the engine's mute is on/off.
- `uid2name` over a match was not driven live on Linux (it needs a server that keeps
  statistics); it takes the same path as `join` (a modal in the game context, `app.menu`).
- `~/.cache/godnotic-audit/base/data` and `~/.cache/godnotic-work/controls-stock/data` link to
  the uninstalled `org.xonotic.Xonotic` Flatpak: every link is dead, so `run-suites.sh` and
  `STOCKBASE=…/controls-stock` test nothing. I used `~/.cache/godnotic-infra/repack/src`
  (`.suite-base` here and `~/.cache/godnotic-work/flatpakui-stock`).
