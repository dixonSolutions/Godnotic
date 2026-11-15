# Handoff: `work/mpfast` (Multiplayer opens in the next frame, with rows)

Base: `origin/work/integration4` 60df869 (round 4: joinfast + joinui) merged with
`origin/main` 4b747d4. Clone `~/.cache/godnotic-work/mpfast`. Both components changed
(`engine/darkplaces`: `touch_uib*.c/.h`, `touch_srvlist.c/.h`, `touch_lan.c`, one hook
line in `sys_shared.c`; `engine/data/xonotic-data.pk3dir`: `touch_uib_screens.qc`,
`touch_uib_api.qh`), so the merge needs `scripts/component-push.sh all` and the pins.
`component-push.sh` was not run here.

## The owner's reports (tablet)

"The multiplayer server list does not open instantly", then "multiplayer servers do not
open until it loads and keeps glitching on player and ping filter".

## What blocked, with evidence

Measured on the tablet with an open trace added for this (`uib_trace`, always in logcat on
Android: `uib: +N ms ...` from the engine, `Native UI: open: ...` from Kotlin), against a
fake world of 300 addresses (240 alive) carried over USB (below), saved list present:

1. **Rows waited for a quarter-second poll and a round trip.** A newly built list had no
   `count` until `UIBSrc_Frame`'s 0.25 s tick, so Kotlin showed an empty list, then got a
   `source` notice, then asked for a window, answered a frame later. Trace (cold run 4):
   diff with the screen at +18.7 ms, `source servers rev 7: 240 rows` at +117 ms, window
   answered +133 ms, rows drawn +168 ms after the tap.
2. **The engine slept through the tap.** Behind a native screen the engine runs at
   `cl_maxidlefps` 20; a tap waited for the next frame (idle run 2: tap at .859, handled
   at .891, 32 ms), and so did each window request (diff +134.7 ms, window +166.7 ms).
3. **The screen was built on the tap's path.** Engine handling 12-25 ms (the server list
   refresh runs inside the action, 8-16 ms of it, before the diff goes out), then 30-35 ms
   of Rust feed and Kotlin view creation, then a 110 ms fade from alpha 0. On a cold
   process the masters are also resolved synchronously in the action (one master took
   75 ms on the tablet's mobile data).
4. **Glitches while it refreshes** (screen recording, `runs/before/glitch`): every
   `source` notice cleared the list to placeholders (a blank list frame after Refresh);
   the player's sort (a header tap) was Kotlin-only, so the next update of the list node
   (its count, during any refresh or filter change) put it back to Ping (Players sort
   reverted after the Empty tap); a filter applied up to 260 ms late (value at 049.30,
   source rev at 049.57); every row rebind set text, typeface and a new ripple drawable.

## What changed

- **Built ahead** (`app.prepared`, builtin `uib_prepare` #1223, cvar `uib_prepare` as the
  feature flag): the menu VM builds Multiplayer while Home shows (only for a native
  renderer). The store keeps prepared roots as app children; Back leaves it prepared.
- **The tap shows it**: Home's card action carries `"opens": "mp"`. The Rust driver
  creates prepared screens' views (`prepared` op) and keeps them after Back; Kotlin holds
  them `INVISIBLE` (laid out, rows bound) in the screen layer and shows the screen in the
  frame after the tap, with Back; the engine's diff only confirms it (no re-add, scroll
  kept); a 1.5 s safety net shows the engine's top if it never opened it. No cross-fade.
- **Rows with the list**: a list that appears is counted in the diff that creates it, and
  its first window (50 rows, its sort/filter) follows unasked (`rows`, `re: 0`). Before
  the first refresh the `servers` source holds the saved rows (stale), so the prepared list
  has rows on a cold start without any packet.
- **Sleeping on the bridge**: `TouchUIB_SleepTime` (hook in `Sys_Frame`) polls an eventfd
  (poked by `TouchUIB_Post`, by the Android JNI queue via `nativeui_set_wake_fd`, and the
  stream socket) instead of sleeping on the clock. Frame rate unchanged.
- **Lists that stay put**: a `source` notice is its own driver op; Kotlin keeps the rows,
  takes the new total at the end, re-asks its visible window and rebinds only changed rows,
  keeping the top row's place (not while touched or flung: then the update waits for the
  list to stop). The sort goes to the engine (`sortAction` with payload `{sort, desc}`,
  QC `server.sort` keeps `mp.sort`/`mp.desc`), and stays the player's until the engine
  agrees. Ties sort by address. A filter applies in the frame it changes. A sent value is
  shown until the engine's props carry it (no checkbox flicker). Row binds set only what
  changed; rows dim without an offscreen layer; header rebuilt only on sort/column change.
- Driver: no full-store clone per batch (`Store::iter`). Schema: `app.prepared`,
  `action.opens`, `list.sortAction`. Docs: `docs/UI_BRIDGE.md` ("Opening a screen fast",
  "Lists that stay put while they update", hooks), `docs/NETWORK.md`.

## Before / after, tablet (SM-X306B, 120 Hz, perf package, fake world over USB)

Before = 9420 (base + open trace only); after = 9422-9426 (opens unchanged since 9422).
Times are ms after the tap (Kotlin click), medians with ranges.

| | screen drawn | rows drawn | list settled |
|---|---|---|---|
| before, cold (fresh app start, n=5) | 66 (55-75) | 140 (107-168) | 0.80 s |
| **after, cold** (n=5) | **7 (7-8)**, same frame | **7 (7-8)** | 0.80 s |
| before, warm (second open, n=5) | 58 (35-66) | 174 (123-315) | 0.80 s |
| **after, warm** (n=5) | **10 (9-13)** | **10 (9-35)** | 0.80 s |
| before, Home idle 50 s (n=3) | 63 (58-67) | 148 (108-214) | 0.80-2.6 s |
| **after, idle** (n=3) | **10 (8-11)** | **10 (8-11)** | — |

Screen recordings (`screenrecord`, frames by their timestamps, deleted from the tablet):
before, 12-17 frames (112-164 ms) from the card's press to Multiplayer visible (the fade),
rows at 132-262 ms; after, Multiplayer with rows is the frame right after the press in 6
of 7 recordings (10-15 ms), two frames (21 ms) in one. The live refresh is unchanged
(srvlist2's network behaviour; settled 0.80 s in this world before and after).

Jank (`dumpsys gfxinfo`, 8.3 ms deadline at 120 Hz):
- open + 5 s of refresh, no touch: before 29-39 frames, 24-35 % janky (7-11 frames);
  after 8-10 frames, 3-6 janky (the remaining misses are the render thread/GPU at
  8.5-15 ms on a frame after idle, with under 3 ms of UI-thread work).
- scrolling the list while a Refresh streams 240 rows: before 0.7-1.3 % (n=3), after
  0.7-2.1 % (n=5), 1-3 janky frames of about 155 either way; janky frames are 8.5-9.3 ms
  with under 1 ms of UI-thread work. **Under 1 % is not reliably met**, before or after.
- the glitch session (sort, filter, sort, Refresh, sort during a refresh): before 33/117
  janky, after 14/73; the recording (`runs/after/glitch`) shows the sort kept through the
  filter change, no blank frame on Refresh, the filter applied in the next frame.

Desktop, the stream transport (what the GTK app uses), `tests/mpfast/mpfast_test.py`:
tap to screen / rows before 24-53 / 28-228 ms, after 1.7-4.1 ms for both; an idle engine
answers a ping in 0.1-0.2 ms (before 12.9 ms median, max 49). QuakeC renderer: unchanged,
the action is handled in 1-3 ms and it draws rows in the same frame (it reads the source
in its own draw).

## Tests

Environment: `.suite-base` with the 20261107 pk3s from `~/.cache/godnotic-infra/repack/src`
and this clone's pk3dir. **The stock 0.8.6 pk3s every suite used are gone from this host**
(the Xonotic Flatpak behind `~/.cache/godnotic-audit/base` is uninstalled), so
`STOCKBASE` suites (`test-native-menu-live.sh`, `test-touch-controls-live.sh`) could not run.
Runner: `~/.cache/godnotic-work/mpfast-tools/suites.sh` (pinned to cores 8-15).

| Suite | Result |
|---|---|
| `scripts/test-mpfast.sh` (new: prepared + rows unasked + no packets; open within 30 ms with rows; Back keeps it; 200 rows streaming with sort and Empty/Laggy filters: every window filtered and ordered, unchanged rows keep their order, sort never undone; fresh list counted with first rows; idle wake) | PASS |
| `scripts/test-srvlist.sh` | all checks passed |
| `scripts/test-uibridge.sh` | all passed |
| `scripts/test-uibridge.sh --engine` | 126 ok, 0 FAIL; fixture check: 5 DRIFT (create, map-preview, seq-map-select, singleplayer, singleplayer-create-lan), the maps' `modes` from the newer data only (Key Hunt gone); the base tree drifts on the same 5 with this data |
| `scripts/test-frontend.sh` | all checks passed |
| `scripts/test-old-name.sh` | PASS |
| `cargo test --offline -p godnotic-loader-core` | ok (fixtures regenerated; `modes` kept from the committed ones) |
| `cargo test --offline -p godnotic-loader-android --no-default-features` | ok, new `a_prepared_screen_is_built_ahead_kept_and_only_navigated`, source op |
| SDL build, `make sv-release`, `scripts/build-web.sh --engine-only` (XT_WEB_DEPS) | build |
| Kotlin | compiled locally in the APK builds (`~/.cache/xt-perf/build-apk.sh`) and by CI |

CI on e98fbe5, all success: `work/mpfast` Tests 43682314760, Android 43682314739, Loader
43682314715; `ci/mpfast` (dispatched by hand: the push did not trigger) Tests 43682333068,
Android 43682335214, Loader 43682337672.

## The tablet test setup (TEST ONLY, in `~/.cache/godnotic-work/mpfast-tools/`)

- `build-apk.sh`: the `.perf` APK from this clone (joinui's script). `menu.dat` must be
  pushed to `files/data/xonotic-data.pk3dir/` (same versionName: no re-unpack).
- `mrelay.c` (NDK) / `mrelay-host.py`: a many-address UDP-over-TCP relay through
  `adb reverse`: the tablet binds the fake world's 301 addresses on loopback, the desktop
  runs `tests/srvlist/fakenet.py` (`genworld.py`, master latency 0.25 s). Nothing of the
  server list goes over the tablet's mobile data. `mpfast.cfg` + `xonotic.args` point the
  masters and the seed at it.
- `tab.sh setup|run <label> cold|warm|idle [n]|teardown`, `openrec.sh` + `openframes.py`
  (recording frame timeline), `glitch.sh`, `fling.sh`, `frames.py` (framestats phases).
- Left on the tablet: `.perf` 1.2.205 code 9426 (this branch) with this branch's
  `menu.dat`; the owner's `config.cfg` and saved list restored; relay and test files
  removed. The menu.dat that was there is `/data/local/tmp/menu.dat.tablet-before-mpfast`.

## Not done / known

- GTK: not run here (the loader with the engine needs the GNOME SDK build and data in its
  sandbox). It gets the engine side (counts and first rows with a new list, the wake, the
  stable sort order, filters at once) but builds no prepared screen and has no
  `sortAction` handling.
- The first-ever open (no saved list) shows the screen at once but its rows only as seed
  servers answer (the seed carries no names to show); starting a refresh before the tap
  would change srvlist2's network behaviour, so it was not done.
- Master DNS still resolves synchronously on the engine thread at the first refresh of a
  process; it no longer delays the screen or its rows, only the live refresh.
- Jank under 1 % while rows update is not met: both builds miss 1-3 frames in 155 by
  under 1 ms on the render thread; the remaining cost is HWUI rendering, not the list code.
