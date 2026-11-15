# Handoff: `work/joinui` (joining from spectating)

Base: `origin/work/integration3` 4697e69 (uifix, joinname, net2, updates2 and the rest).
Clone `~/.cache/godnotic-work/joinui`. Both components changed (`engine/darkplaces`:
`touch_hud.c`, `touch_hudmode.c/.h`; `engine/data/xonotic-data.pk3dir`: the join popup and the
QuakeC join dialog), so the merge needs `scripts/component-push.sh all` and the pins.
`component-push.sh` was not run here. Nothing in netconn / cl_parse (work/joinfast's).

## The owner's report (tablet, perf 1.2.204, a public CTF server)

"no join button, did not see the dialogue from xonotic I asked so long for you that comes
with it (possibly we can customize)". Logcat: the join dialog opened 0.6 s after the match
came up (`dialog over the match: [join]` at 20:36:50.096) and a tap 0.3 s later closed it
(`dialog over the match closed` at .426): the popup was `dismissable: true`, so a tap on the
scrim was Back. The HUD had no join control: the swipe-up handle was removed in `db6cd1c`
(work/controls) because a swipe up from the bottom is Android's Home, and joining became the
dialog alone. The owner's later request, a large swipe-right toggle, was never built.

## What changed

1. **Swipe to join** (`touch_hud.c`, rules in `touch_hudmode.h` `TouchSlide_*`). While
   spectating (free-fly or following): a green track at the bottom middle between the
   rocker and the spectator bar, SWIPE TO JOIN (CLICK TO JOIN at a desk), knob at the left
   end, a chevron trail lit in turn, the knob nudging right every 2.4 s (none with
   `touch_reduce_motion`). Knob past 70 % and let go: `touch_join_slide` (team game: the join
   dialog; else `touch_join auto`). Short: springs back. A tap or a click (pointer free) is a
   full swipe. Up or down first: handed to the look. JOINING / IN THE QUEUE / LOCKED while the
   server answers. Hidden while playing, dead, out of the round, at match end, and whenever a
   menu, the console or a CSQC pointer UI has the screen. 56 dp tall, up to 300 dp wide, 48 dp
   from the bottom and side edges; above the bottom row when the gap is under 200 dp (small
   phones). Engine overlay, fingers taken before the finger table: the same under a server's
   CSQC (engine HUD) and the port's (local games), on Android, Linux and web.
   `touch_join_slider 0` hides it. `TOUCHCTL_JOIN` (bit 19) is set again for spectating and
   following.
2. **The join dialog is seen and deliberate.** `dismissable: false` (Kotlin: no scrim tap;
   GTK: `can_close` false, Escape sends Back; the QuakeC dialog never closed on a tap
   beside it), `guardMs: 450` (new screen prop: Kotlin intercepts touches, GTK makes the
   content insensitive, the QuakeC dialog eats presses), and the engine never opens it within
   0.5 s of a menu closing (`TOUCHJOINDLG_FREE`), so it cannot race or merge with the pause
   page. Kotlin fades and scales it in over a slightly darker scrim (0x73 instead of 0x59).
   Over the match the pause page is not shown under it (unchanged, android3's
   `syncOverGame`), and a choice returns to the match.
3. **Xonotic's team selection, our style.** "Join the match"; Auto-select team (primary);
   the teams the server offers as tall tiles in their colours with "N players" (button props
   `swatch`, `team`, `subtitle`; Kotlin `teamTile`, GTK `team_tile` + `gn-team-<team>` CSS);
   Spectate. No teams: Join (with "N players in the match") and Spectate. Counts come from
   the engine's scoreboard read (`_touch_team_players` "r b y p", `_touch_match_players`) and
   follow while the popup is up. The QuakeC dialog (web, no native front end) shows
   "Red · 3". Stock commands underneath (`cmd join`, `cmd selectteam`, `cmd spectate`);
   Spectate and `touch_join_dialog_auto 0` are honoured (unchanged logic).
4. Schema (`scripts/uib-schema.py`, `docs/ui-bridge.schema.json`): `guardMs` on screens,
   `swatch` and `team` on buttons. Docs: `docs/CONTROLS.md` (Joining rewritten, HUD modes,
   cvars), `docs/UI_BRIDGE.md`.

## Tests (all on this tree; commands from the clone root)

Commits (merged with origin/main 35dcf95 in c5153cc; CI green on it): f6b2510 (the work), 4e7a93b (teams share the row: grid `maxColumns`, Kotlin
honours it), 3eb9c41 (QuakeC dialog's team labels were blank: `Label.setText` keeps the
pointer, the `sprintf` result died a frame later; the CSQC prompt says "Press SWIPE to join").

Environment for the live suites: `.suite-base` (stock pk3s + this clone's pk3dir),
`STOCKBASE=~/.cache/godnotic-work/controls-stock`, `LIBS=~/.cache/godnotic-slimtest/lib`,
`PATH=~/.cache/xt-assets/bin:$PATH`, `CPUS=12-15`. Runner: `~/.cache/godnotic-work/joinui-tools/suites.sh`.

| Suite | Result |
|---|---|
| `scripts/test-touch-hudmode.sh` | pass (slider maths, free-screen settle, join bit per mode, team bits) |
| `scripts/test-touch-controls.sh` | pass (slider layout at 1920x1200, 2340x1080, 1280x720, small phone; both hands; insets) |
| `scripts/test-touch-spectate.sh` | pass |
| `scripts/test-old-name.sh` | pass |
| `scripts/test-uibridge.sh` / `--engine` | pass / pass |
| `scripts/test-native-menu-live.sh` uid2name, joinmid, joinname | pass |
| `scripts/test-native-menu-live.sh joinui` (new, DM + CTF) | pass, 39 checks; rerun after 4e7a93b with the `maxColumns` check: pass |
| `scripts/test-touch-controls-live.sh rects` | pass (JOIN meets nothing at tablet, phone, desk) |
| `scripts/test-touch-controls-live.sh join local` | pass (after 3eb9c41) |
| `scripts/test-touch-controls-live.sh input` | FAILS on a 4-core pin, and so does the base engine (integration3's touch_hud*, same QC): base failed both attempts in one of two samples, mine in three of three; failures scatter (lost finger-ups, flicks, a short slow swipe) across local and remote HUDs, in states where the slider is hidden and its hook returns at once. Load flake; needs 8 cores (the controls agent ran it on 16-23) |
| `cargo test --offline -p godnotic-loader-core` | pass |
| `cargo test --offline -p godnotic-loader-android --no-default-features` | pass (new `the_team_join_popup_carries_colours_counts_and_its_guard`) |
| `make sv-release`, `scripts/build-web.sh --engine-only` | build |
| CI on `ci/joinui` (dispatched by hand: the push did not trigger at first) | Tests, Android (3 APKs), Loader (core, GTK x86_64 + aarch64) green on 4e7a93b; Tests and Android green on 3eb9c41 |

## On the tablet (perf 1.2.205, this branch's engine and Kotlin, local stock 0.8.6 server)

The tablet was on mobile data (no Wi-Fi), so the game ran over USB: a stock dedicated
server here, `joinui-tools/udprelay.py` (desktop) and `udprelay` (NDK build, run from
`/data/local/tmp`) carrying UDP over `adb reverse tcp:26100`, `+connect 127.0.0.1:26000`
in `files/xonotic.args`. Screenshots in `~/.cache/godnotic-work/joinui-shots/tablet/`:

- `01`: at connect, Kotlin's dialog over the match: "Join the match", Red and Blue tiles
  in their colours with "2 players" each, Spectate, Auto-select team (taken before
  4e7a93b: the tiles filled half the row, fixed since but not reinstalled, see below).
- `02`: after three taps beside it (corner, bottom middle, right edge): still up.
- `03`: Red tapped: in the game on Red, no slider.
- `04`, `05`: MENU > Spectate; Swipe to join on the spectator HUD, no dialog nagging.
- A real `input swipe` on the knob: the dialog opened 17 ms after release (logcat
  13:15:58.030). Then someone at the tablet answered it by hand (`06`: in the game on Blue)
  and played on; when the next match loaded the dialog came up by itself 0.7 s after the
  map and stayed 5 min 43 s, untouched, until one tap answered it.

Not done on the tablet: the DM case (tap on the slider joins) and the 4e7a93b/3eb9c41
build (APK 1.2.205 code 9416 built at `~/.cache/godnotic-tabtest-joinui/out/`; not
installed, because the owner was playing on it). `menu.dat` / `csprogs.dat` from 3eb9c41
are pushed into the `.perf` files dir (they apply at the next start; the old ones are in
`/data/local/tmp/*.tablet-before-joinui`). The test server stops by itself (40 min, about
13:54); a watcher then stops the desktop relay, removes the `adb reverse`, kills the tablet
relay and deletes `files/xonotic.args` (it writes `joinui-tools/cleanup.done`). To install
the newer build: `adb install -r ~/.cache/godnotic-tabtest-joinui/out/Godnotic-1.2.205-arm64-v8a.apk`.

Offscreen screenshots (`~/.cache/godnotic-work/joinui-shots/`): the slider on the engine
HUD (`offscreen_engine_hud_spectating_slider.png`), mid-swipe (`*_slider_mid_swipe.png`),
over the port's CSQC in a local match (`local_csqc_spectating_slider.png`), the QuakeC
dialog in CTF ("Red · 1", "Blue · 2") and DM (`*_dialog_at_connect.png`).

## Not done / known

- The stock "Press SPACE to join" line on a public server is that server's CSQC
  (`getcommandkey` through `findkeysforcommand`); no Touch hook on that path without editing
  upstream `prvm_cmds.c`. The slider sits right under it, labelled.
- GTK: compiled and clippy'd by CI (Loader workflow); not run on screen here.
- The QuakeC dialog keeps the stock four-column team row, so two teams leave a gap, and in
  DM there is an empty band where the team row would be (noted by work/controls too).
- Swipe to join is drawn by the engine; the port's CSQC does not draw its own copy, so its
  `touch_hud_state rects` (engine HUD only) is the one place the slider's rect is printed.
