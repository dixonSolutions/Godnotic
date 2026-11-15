# Handoff: faster joins and local starts (`work/joinfast`)

Scratch clone `~/.cache/godnotic-work/joinfast`, branch `work/joinfast`:
`origin/main` (7a8d3ee) + `origin/work/net2` (d9e2b51) + `origin/work/srvlist2`
(1743c0b) merged first (one conflict: `HANDOFF.md`, deleted on main, kept
deleted), then this work. Design, numbers and what is left:
[`docs/JOIN_SPEED.md`](../JOIN_SPEED.md).

## Done and verified (desktop)

- **Timeline** `touch_jointime.c` (`touch_jointime 1`, `_touch_jointime`,
  `_touch_jointime_serial`): tap, spawn, challenge/connect/accept,
  signon 1-4, curl packs, CSQC fetch/download, CSQC init, precache, first
  frame, playable. `scripts/bench-join.sh` runs local starts and joins
  (this checkout's server or the real Xonotic 0.8.6 `xonotic-dedicated` from
  the Flatpak) through netem and prints medians per phase.
- **A stock server's CSQC over HTTP** `touch_csprogs.c`: known release
  builds (0.8.6 today) fetched from `touch_csprogs_url` (default: the
  prerelease `csprogs` of dixonSolutions/Godnotic) as raw deflate, checked
  against a compiled-in SHA-256 and the CRC, written to dlcache where the
  stock code looks; started from the stock server's first reliable fragment
  (peek hook in `netconn.c`), held load via one hook in `CL_BeginDownloads`.
  Every failure (404, garbage, slow, no zlib) falls back to the server's
  UDP download. Stock 0.8.6 join at 100 ms RTT: 23.7 s -> 3.7 s; at 200 ms:
  26.6 s -> 6.4 s.
- **Preload early** (`touch_preload_early`, `touch_preload.h`): on a
  remote server the per-map preload replay is resumable and starts at the
  end of the precache, 15 ms slices, overlapping prespawn/spawn.
- **Join warm-up** (`touch_warm_join`, `touch_warm.c`): the map's preload
  list loads while a join waits on the network (stock serverinfo
  stop-and-wait, gated to the first 40 % of each round trip; CSQC download).
  Never the world model.
- **Font slices** (`touch_load_fontslices`): pending font sizes render in
  the network waits instead of 240 ms in the frame after `accept`.
- **Crash fix**: the menu warm-up could start a model load from inside its
  own world model load (via the loading-screen redraw in
  `CL_KeepaliveMessage`) when a server was tapped: 2 of 5 joins at 200 ms
  crashed on `origin/main`. Fixed (no warm-up inside a load or during a
  join); regression case `test-joinfast.sh warmjoin` (fails 2/5 on the old
  binary, passes 5/5 now).
- Hooks in upstream files, each marked `// Touch:`: `cl_parse.c` (include x2,
  `TouchJoinTime_Signon` in `CL_SetSignonStage_WithMsg`, `TouchCSProgs_Wait`
  in `CL_BeginDownloads`; `CL_ResumeHeldLoad` lost its `__ANDROID__` guard,
  Touch's own lines), `netconn.c` (include, `TouchCSProgs_Peek`),
  `makefile.inc` (2 objects).

## Numbers (desktop, median of 5, first frame from the tap)

| | before | after |
|---|---|---|
| stock 0.8.6 server, 100 ms RTT | 23676 ms | 3744 ms |
| stock 0.8.6 server, 200 ms RTT | 26570 ms (2 of 5 crashed) | 6365 ms |
| stock 0.8.6, CSQC cached | 3977 ms | 3689 ms |
| stock server, unknown build (UDP CSQC) | 23676 ms | 23308 ms |
| Godnotic server, 100 ms RTT | 1481 ms | 1305 ms |
| local Play after 3 s on the menu | 761 ms | 785 ms (same) |
| local Play 0.3 s after the menu | 1546 ms | 1551 ms (same) |

Phase tables: docs/JOIN_SPEED.md "Results".

## Not done / not verified

- **The release is not published.** After merging, run the workflow
  `CSQC builds for joins` (`.github/workflows/csprogs.yml`, workflow_dispatch
  only, so it exists once on main): it downloads the official
  `xonotic-0.8.6.zip` on the runner (about 1 GB, GitHub's bandwidth),
  makes `csprogs.dat.4021257.18349.deflate`, checks it against `tcp_known`
  and uploads it to the prerelease `csprogs` (created with `--latest=false`,
  so `releases/latest` and the app updater never see it). Until then clients
  get a 404 and fall back to the server, exactly as before (one HTTP request
  per stock join; no time lost: it runs during the serverinfo).
- Not measured on the tablet (owned by other agents during this work), nor
  against real public servers after the change, nor a real GitHub download
  (TLS + redirect add a few round trips, still hidden at the RTTs tested).
- Only 0.8.6 is in `tcp_known`. 0.8.5 (6 public servers) needs its
  csprogs.dat from the 0.8.5 archive: add it to the workflow and the table
  (`scripts/csprogs-catalog.py` prints the line). Autobuild/git servers (76
  of 243) run their own builds and keep the UDP download (~20 s at best).
- Ideas not done (JOIN_SPEED.md "What is still slow"): CSQC download
  alongside a server's packs (a stock server cannot cancel a download, so
  it costs 20 s whenever a pack brings the CSQC); world model during the
  waits (crashed/stalled in the signon; the warm-up fix above may have been
  the cause, untested); pipelined signon to Godnotic servers (NETWORK.md).

## Test results (2026-11-14, this clone, `taskset -c 4-9`)

New: `scripts/test-joinfast.sh` (logic always, also in CI's Tests workflow;
engine cases with `BASEDIR`), `tests/joinfast/csprogs_logic_test.c`.

```
BASEDIR=$PWD/.join-base LIBS=~/.cache/godnotic-slimtest/lib scripts/test-joinfast.sh
  -> 24 ok, "test-joinfast: all passed" (timeline, http, missing, corrupt, slow, off, warmjoin)
     warmjoin on the old binary (ENGINE=xonotic-jt0): "2 of 5 crashed" (FAIL), now 5 of 5
bash ~/.cache/godnotic-work/run-suites.sh <clone> <logs> 4-9
  build rc=0, wasm rc=0 (build-web.sh --engine-only, XT_WEB_DEPS), sv rc=0 (make sv-release)
  old-name, cargo-core (0 failed), cargo-android (0 failed), uibridge, touch-spectate,
  touch-hudmode, lan-logic, host-resources, frontend, lan, lan-private, lua-sandbox,
  lua-match, join-world, srvlist, joinfail, net (all net tests passed), uib-engine,
  mods (111 of 111), touch-controls, mapmode, notify-lines: rc=0
  touch-controls-live, native-menu-live: rc=77 SKIP (need STOCKBASE)
  net-away: rc=124, the suite's 1200 s step timeout, after 23 ok and no FAIL;
    the remaining cases run on their own: "all away tests passed"
    (mapchange-away manual-away connect-again restart-signon)
  datafetch: rc=1 only because run-suites calls it without the split dir (usage error)
BASEDIR=.suite-base scripts/test-net-connect.sh             -> "connect timing: ok"
ENGINE=build/bin/xonotic scripts/test-datafetch-engine.sh ~/.cache/godnotic-work/data-out/split-G3
                                                            -> "PASS: datafetch engine"
scripts/test-datafetch.sh ~/.cache/godnotic-work/data-out/split-G3 -> first run: one FAIL
    "resume fetched 33013760 bytes more than the set" (the Rust loader's downloader after
    a SIGKILL; nothing of this branch touches loader/); rerun: "PASS: datafetch"
```

CI on `ci/joinfast` (df19187): Tests, Android and Loader (dispatched by
hand) green. The first push failed to parse tests.yml (a colon in a step
name); fixed in df19187.

Bench data: `~/.cache/godnotic-work/joinfast-tools/m-base{,.txt}` and
`m-after{,.txt}` (logs per run), runner
`~/.cache/godnotic-work/joinfast-tools/run-matrix.sh base|after OUT`.
