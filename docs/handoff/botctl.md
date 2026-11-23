# HANDOFF: Server control bots (work/botctl)

## Root cause of "adding bots only ever adds ONE bot"
Not the rcon commands (one `bot_number N` is correct and the server adds all N). The local
game is started with `maxplayers = max(2, bots + 1)` (QuakeC `UIB_StartMatch` and the Android
front end's `fe_play` in `touch_frontend.c`). Every local game starts with 1 bot, so it gets
2 slots, and `maxplayers` cannot change while a match runs: `bot_fixcount` caps bots at
`maxclients - realplayers`, so any later "N bots" stays at 1 bot. Reproduced live: a dedicated
server with `maxplayers 2` and a connected player stays at 1 bot after `bot_number 5`.

## What changed
- Local games start with 33 slots (`FE_LOCAL_SLOTS` in `touch_frontend.c`; `touch_uib_screens.qc`).
- `sv_cmd touchbots <n> [red|blue|yellow|pink|auto]` (`qcsrc/server/touch_bots.qc`, Touch-owned,
  one include line in `progs.inc`): sets the count at once (stock `bot_number` adds one bot per
  server frame) and puts the added bots on the chosen team in a team game; balanced otherwise.
  Prints a `touchbots: want= bots= ...` summary line. Servers without it ignore it.
- Server control (`UIB_BuildControl`, rendered natively by Kotlin/GTK): groups Bots (count 0-32,
  skill Easy..Godlike, team Balanced/Red/Blue, one "Apply bots"), Match (map, time limit, frag
  limit, one "Apply limits", More time, Restart), Server (kick), Cheats. Apply sends
  `skill`, `bot_number`, `sv_cmd touchbots` (stock servers act on the first two).
- Remembered values: archived cvars `touch_ctl_bots/skill/team/timelimit/fraglimit`
  (`touch_lan.c`); a game this device runs shows its real values on open.
- Fixture `tests/uibridge/fixtures/control.json` regenerated from `seed_fixtures.py`.

## Tests
- `scripts/test-botctl.sh` (new): `tests/botctl/test_ctl_server.py` (dedicated server + a real
  client: maxplayers 2 cap, 33 slots give 6 and 20 bots, touchbots red/auto/blue split) and
  `tests/botctl/test_ctl_ui.py` (real menu QC, local game, tap Apply with 6 bots, count them).
Results (this clone, engine built with `make sv-release` and `sdl-release`):
- `scripts/test-botctl.sh` parts: test_ctl_server.py all passed; test_ctl_ui.py all passed.
- `scripts/test-uibridge.sh`: all passed. `--engine`: engine_test, input_test, firstrun all passed; the
  final `uib-fixtures.py --check` reports DRIFT on settings/mod-page fixtures (not touched here;
  not compared against origin/main). The control fixtures are not among them.
- `scripts/test-frontend.sh`: all checks passed.
- `cargo test --offline -p godnotic-loader-core`: 20 + 13 passed (fixtures.rs updated to the new ids).
- `scripts/build-web.sh --engine-only`: built.
- The engine harness prints two `schema: ... home/tiles/mp rows more than 8 items` lines when
  Home has the synthetic test rows; they predate this work (test_ctl_ui.py filters them).

## Not done / known
- Team choice only works on servers running this QuakeC (local games, Godnotic-hosted LAN);
  another stock server gets balanced bots. A remote server's slot count is not known to the
  screen, so it silently keeps fewer bots than asked when full.
- Gametype change is not in the screen (`sv_cmd gametype` applies at the next map).
- LAN hosting keeps the Host screen's slot slider (default 8, up to 32).
- No Kotlin change, so no Android/CI Kotlin test was added; Android is built in CI only.
- Nothing checked on a tablet.
