# Handoff: `work/mpsort` (the server list's sort sticks and is remembered)

Base: `origin/work/integration4` 46a1de4 (round 4: joinfast, joinui, mpfast, flatpakupd), with
88fde5e (flatpakrepo) merged in.
Clone `~/.cache/godnotic-work/mpsort`. One component changed: `engine/data/xonotic-data.pk3dir`
(`touch_uib_screens.qc`, `touch_uib_render.qc`, both Touch-owned), so the merge needs
`scripts/component-push.sh data` and the pin. `component-push.sh` was not run here.
`engine/darkplaces` is unchanged (the engine binary used is mpfast's build of the same tree).

## The owner's report

"the server list, try filtering by players and it just resets back to zero player servers
by ping... it should remember the top filter, there is just one." Most likely the Linux
Flatpak (GTK front end).

## What was wrong (GTK, reproduced headless against the real engine)

`scripts/test-gtk-mpsort.sh` with the base tree's loader (`listsort` lines: the header's
sort, the scroll position, then name:players of the first rows):

```
open      sort=-       desc=0 top=0    Fake 000:0, Fake 154:0, Fake 031:0 ...   (no sort shown)
Players   sort=players desc=0 top=0    Fake 100:0, Fake 101:0, Fake 103:0 ...   (ascending: empty first)
scrolled  sort=players desc=0 top=1566
+0.7 s    sort=-       desc=0 top=0    Fake 000:0, Fake 154:0 ...               (back to Ping, scroll lost)
reopen    sort=-  ...  restart  sort=- ...
```

1. GTK never sent the sort to the engine (no `sortAction` handling), so the menu VM kept
   building the list with `sort: ping`.
2. Any update of the list node (its `count`, several times while the list refreshes) and
   any new screen subtitle ("N servers · refreshing…") rebuilt the whole Multiplayer screen
   from the node: Ping again, scroll at the top, chosen row gone.
3. The header showed no sort at first, and a first click on Players sorted ascending: the
   empty servers first.
4. Nothing remembered the sort past the menu VM's session variable (`mp.sort`). The
   QuakeC renderer (web) kept its sort locally and never told the menu VM.

## What changed

- **Saved sort** (`touch_uib_screens.qc`): `server.sort` stores the column in the archived
  cvars `touch_mp_sort` (a sortable column key; anything else reads as `ping`) and
  `touch_mp_sortdesc`, registered with `CF_ARCHIVE` (32). The list's `sort`/`desc` come from
  them, so every build, Back, and a restart (config.cfg: written on quit, on Android when
  paused, on the web within seconds by its autosave) keep it. Unknown columns are refused.
  The session variables `mp.sort`/`mp.desc` are gone.
- **GTK** (`loader/crates/gtk/src/widgets.rs`, list and screen code only):
  - a header click sends `sortAction` with `{sort, desc}` (as Kotlin does), keeps the
    player's sort until the engine's props say the same, and starts the new order at its top;
    a first click on a count column (`players`, `number`) sorts most first;
  - the header shows the list's sort from the start (set quietly, not as a click);
  - an update that changes only `count`, `selected`, `sort` or `desc` of the server list
    leaves the view in place (an engine-side sort change is followed in place); a list that
    goes empty or gets its first rows is still rebuilt (the empty page);
  - a screen whose `subtitle`/`title` alone changes sets it in place instead of rebuilding;
  - the chosen row is shown again after a rebuild and after rows are replaced by a refresh.
- **QuakeC renderer** (`touch_uib_render.qc`): a header tap sends the same `server.sort`
  action; the second tap turns the sorted column around from what is shown (it used to read
  a stale local flag); a count column starts most first; the local sort gives way once the
  menu VM carries it. Test hook: `uib_input tap <list>@<column>` finds a header cell.
- **Android**: no Kotlin change (mpfast already sends `sortAction`); it gets the saved sort
  from the menu VM.
- Test aids in `loader/crates/gtk/src/script.rs` (a small hunk next to work/flatpakui's
  lock): `sort <list> <column>` (what a header click does) and `listsort <list> <file>`.
- `loader/scripts/run-headless.sh` taken from `origin/work/flatpakui` 07d5e2c (the
  sandbox gets no display socket; see "Known" below).
- Docs: `docs/UI_BRIDGE.md` ("Lists that stay put while they update").

## After

Same run with this branch (`~/.cache/godnotic-work/mpsort-logs/gtk-test/first.txt`):

```
open      sort=ping    desc=0 top=0    Fake 000:0, Fake 154:0 ...
Players   sort=players desc=1 top=0    Fake 105:15, Fake 009:15, Fake 153:15 ...
scrolled  sort=players desc=1 top=1576
refresh   sort=players desc=1 top=1576 (x4, 0.3 to 5 s into the refresh, rows updating)
reopen    sort=players desc=1
restart   sort=players desc=1          (then the engine sorts by Ping itself: followed in place)
```

Screenshots of both runs (GTK's own renderer, `snapshot`):
`~/.cache/godnotic-work/mpsort-logs/evidence/gtk-before/` and `.../gtk-test/`.

## Tests

Environment: `.suite-base` = stock 0.8.6 pk3s + this clone's pk3dir for engine suites; the
GTK runs need pk3s the Flatpak sandbox can read, so `GTK_BASEDIR` =
`~/.cache/godnotic-work/mpsort-tools/gtkbase` (the 20261107 pk3s + this clone's pk3dir).
Runner: `~/.cache/godnotic-work/mpsort-tools/suites.sh` (cores 4-7).

| Suite | Result |
|---|---|
| `scripts/test-mpsort.sh` (new: `tests/mpsort/mpsort_test.py` native renderer 12 checks + QuakeC renderer 8 checks, then `scripts/test-gtk-mpsort.sh` 9 checks) | PASS, 29 ok; run with `:0` watched, no window there |
| `scripts/test-gtk-mpsort.sh` against the base tree's loader | 6 of 8 checks FAIL (the "before" above) |
| `mpsort_test.py` QuakeC part with the renderer's report taken out | 6 FAIL (sort stays Ping, not saved) |
| `scripts/test-mpfast.sh` | PASS |
| `scripts/test-uibridge.sh` | all passed |
| `scripts/test-uibridge.sh --engine` (stock 0.8.6 data) | 152 ok, 0 FAIL; fixture check: 1 DRIFT, `map-preview`'s mode list from the 0.8.6 data (the committed fixture says "Clan Arena and 10 more", the data gives "Domination, Last Man Standing and 6 more"); not from this branch. Fixtures not regenerated: the tree this branch builds is unchanged at the default sort |
| `scripts/test-srvlist.sh` (ENGINE set) | end to end: all checks passed |
| `scripts/test-frontend.sh` | all checks passed |
| `scripts/test-gtk-screens.sh`, `scripts/test-gtk-layout.sh` | all passed (after the merge, fixed run-headless) |
| `scripts/test-old-name.sh` | PASS |
| `cargo test --offline -p godnotic-loader-core` / `-p godnotic-loader-android --no-default-features` | ok / ok |
| `loader/build.sh --check` (SDK 50: build, clippy -D warnings, core tests with portal) | ok, before and after the merge |
| QuakeC build | ok; SDL engine not rebuilt (darkplaces unchanged; mpfast's build of the same tree) |

CI on 1cccfb4, all success (dispatched by hand: the push did not trigger): `work/mpsort` Tests
43688385920, Android 43688388096, Loader 43688390427; `ci/mpsort` Tests 43688393033, Android
43688395065, Loader 43688397241.

## Not done / known

- **Earlier GTK runs of this branch drew on the owner's desktop.** Before the
  run-headless fix arrived (about 17:33-17:51 on 2026-11-15) the repro runs, the first
  `test-gtk-mpsort.sh` runs and a partial `test-gtk-screens.sh`/`test-gtk-layout.sh` pass
  went through the old `run-headless.sh`; the engine log of those runs says
  `Display 0: DP-1`, i.e. the owner's monitor, and the GTK window was on `:0` too. Every run
  since uses the fixed script and an offscreen engine (`Display 0: 0`), with `:0` watched
  (`xwininfo -root -tree`) during the run: no Godnotic window.
- Nothing checked on the tablet (owned by work/mpfast); Android gets the change only through
  the menu VM, which `tests/mpsort/mpsort_test.py` covers with a native renderer.
- The web build itself was not run in a browser; the QuakeC renderer was tested in the
  desktop engine (the same menu.dat), and its save on the web relies on the existing
  archived-cvar autosave.
- GTK's Back drops the Multiplayer page, so a reopened list starts at its top (sort kept).
- `registercvar(..., 1)` in `UIB_Init` (`_uib_recent`, `touch_lan_bots_duel`) passes
  `CF_CLIENT`, not `CF_ARCHIVE` (32): those are not saved unless a cfg `seta`s them. Not
  changed here.
