# HANDOFF: work/srvjoin (server join: card, download display, join timings)

Base: origin/main 2362f283 (v1.2.318), merged again before the last push.

## Verified on origin/main (no change needed)
- Android join card is bottom-docked since 1.2.317 (FrontendHost applyLook gravity BOTTOM, insets listener). One gap fixed:
  the card's screen was created with CENTER_HORIZONTAL (top) and only moved down by the first applyLook.
- Engine fallback: SCR_DrawCurlDownload and the loading bar lay out from the bottom edge. The QuakeC UIB "loading"
  screen (web / GTK fallback) was top-flowed: fixed (touch_uib_render.qc).

## Done
- JoinFlow.CARD_GRAVITY (bottom) from the first frame; QC loading screen docked at the bottom.
- The server's in-protocol csprogs.dat download is a download on the card (touch_netdl.c TouchNetDL_Progress,
  touch_frontend.c connect topic dl_* fields): "Downloading the server's game code", MB of total, speed, time left.
- One bar that moves while the server's first data arrives (JoinFlow.overall waiting/signon), then downloads, then load.
- Metered: app sets cvar touch_net_metered per join (Net.kind); card says "mobile data" + MB used; engine caps the
  map-picture range read to touch_net_metered_shot_kb (256) when metered.
- scripts/bench-srvjoin.py: per-server timing harness (picture ms, right map, first data, csqc, in game).
- docs/NETWORK.md: table of real-server timings and where the time goes.

## Timings (cold, RTT 214-280 ms; ms after Join)
| server | before in game | after in game | picture (kind, right map) |
|---|---|---|---|
| Astral Bane 173.249.194.249 | 6810, 6360 | 6229, 6674 | 421 (list, yes) |
| Good Place 130.61.215.219 | 19359, 19396 | 18888, 19144 | 544-563 (list, yes) |
| gianmarco 159.195.41.211 (custom map, 15-21 MB) | 8273 | 8588, 8842; warm 8521 | 1547 before / 2077 (range-read) |
| Relax Amsterdam 194.87.79.246 | 19276 | server stalled the csprogs download 64 s (variance) | 549-609 |
Join time did not change: it is protocol-bound (22 x RTT stop-and-wait signon on stock servers; csprogs.dat 9 s at one
block per server tick). The evidence is in docs/NETWORK.md. Hightime quark_ht (custom map) was aborted at the 70 MB cap.
Evidence (logs, first-picture files, json): build/srvjoin/ (not committed; per-run `*-picture.jpg` is the picture the card showed first).
No engine screenshot of the world was captured (offscreen run took none): not done.

## Preview steady, popup and first-frame picture (owner additions)
- Flash causes fixed (Kotlin): the shown bitmap was nulled on every picture change and on every snapshot without a picture;
  now JoinFlow.nextShot keeps it (only a better kind of the same map replaces it, cross-faded in place, other map's at once);
  the world takes the screen only after 600 ms drawable (WORLD_SETTLE_MS). Engine side checked: touch_joinpreview already shows
  the world only when no picture exists and keeps the picture inside R_Modules_NewMap; real-server logs show no blank engine snapshot.
- Placeholder: the map-name banner had its own gradient above a differently coloured screen, leaving a band behind the card:
  one gradient is now the screen's background (nameBackdrop). Picture from the first frame: engine's, else the dialog's, else a
  fetched map picture, else art generated from the map name (MapShots.fallback), so Join never shows a bare box.
- Server-info popup: no bare box for a custom map: cached picture, else the generated art at once, a small picture fetched from
  MapShots.sources fading over it; the list prefetches the first rows only on unmetered links; negative cache 6 h; <=400 KB.
  HONEST: no public map-picture host was found that is safe to automate (xonotic.org requires bot verification), so
  MapShots.sources points at a Godnotic-data release "mapshots" that nothing publishes yet; until it exists Feris2/sinister_full_r1
  shows the art. A workflow that publishes mapshots/<map>.jpg is the next step. Not measured on a device: the popup/card first
  frame is synchronous by construction (no network), unit-tested for choice, not timed on the tablet.
- Card text "Connecting…"; the bar never goes back (JoinFlow.monotonic).
- Per server, card picture (engine, ms after Join, build/srvjoin/pv-*): Feris2 130.61.138.118 sinister_full_r1: engine names none
  (art from 0 ms); Hightime 74.208.139.247 minideck_v4r4: list picture 1565 ms (art/hint 0 ms), steady; japandm_ht 31.220.18.52: 3612 ms
  (steady, then own picture 4391). preview_steady.py ok on all 14 real runs (no blank, no revert).

## Follow-up: the real picture, not art (Feris2, Hightime)
- Feris2 diagnosis (measured against the server's own pack, Range): http://209.25.141.16:61293/sinister_full_r1.pk3 serves Range
  (Caddy, 206) and holds maps/sinister_full.jpg (240 KB), NOT maps/sinister_full_r1.jpg: the picture of the first revision. The
  engine looked for the exact name only. Fixed: touch_zipshot.h tzs_find cuts the name back at '_' (sinister_full_r1, sinister_full,
  sinister; 4+ letters); unit-tested (tests/touch-zipshot) and scripts/test-touch-join-shot-revision.sh (local server, pack holding
  maps/joinshotfx.jpg for map joinshotfx_r1, picture shown before the pack finishes).
- Hightime Chill XDF (mccormic-mark): cts_mccormic-mark.pk3 (5 MB) holds levelshots/mccormic-mark.jpg (138 KB); the other 4 packs
  (csprogs, mapmedia, q3 compat, tp-egypt0tga 30 MB) hold none, so the picture is in the map's own pack. The engine's early read needs
  the server's "--for maps/<map>.bsp" token; if the server omits it the early read never started and the picture waited for that
  pack's turn in the queue (the owner's file 3 of 5). Fixed: touch_joinname.c peek_curl_line also takes the pack whose file name carries
  the join's map name (touch_packname.h tpn_url_names_map, unit-tested). Not confirmed against Hightime itself (see below).
- Popup, no hosting: ServerShots.kt does a polite probe (getchallenge, connect, first signon fragments, 3 x clc_disconnect; verified
  against a dedicated server: "Disconnect by user", no slot held) to learn the pack URLs, then Range-reads the picture (EOCD,
  central directory, one entry; 2 requests). Measured with the same logic in scripts/mapshots-harvest.py: probe 0.53-0.62 s; Range
  reads 1.2 s (deck16][ pack), 1.7 s (evilspace_r1), 4.4 s (Feris2's pack host is slow); Feris2 real picture 252 KB, correct (Sinister).
  Cached as <map>.jpg with <map>.id (zip entry CRC-32 and pack name); a second open or join shows it at once; metered: only <= 256 KB packed.
  The card prefers the fetched real file over the dialog's art, the engine's own over both; art -> real is a cross-fade.
- mapshots release: .github/workflows/mapshots.yml (workflow_dispatch only, contents: read, uploads to a Godnotic-data release,
  never a branch), tests/mapshots/workflow_test.py. Not triggered, not pushed to main.
- Wedge: JoinNoWedgeTest models the composition (backdrop = screen background over all rows, banner transparent) and the gradient has
  no step; a pixel render of the Android or QuakeC join screen was NOT possible here (no Robolectric/emulator in the harness, the QC
  loading screen cannot be entered headless). Please check on the tablet.
- NOT measured: Hightime / Feris2 before-after Join-to-picture timings. A live re-join was denied by the session's permission
  classifier after many earlier joins and downloads from public servers, and I did not work around it. Earlier numbers (before the
  name-chain fix): Feris2 engine picture never; Hightime minideck_v4r4 list picture 1565 ms. Please run
  `scripts/bench-srvjoin.py -n 1 --label x -o build/srvjoin 130.61.138.118:26000 74.208.139.247:26017` (preview_steady.py is applied to each run).
- Hook wanted from the download owner (dlfast), not edited here: none required; the picture reads run on their own curl handles
  beside the downloads (touch_curlshot.h) and hold early downloads back at most 2.5 s (touch_joinname.c hold_until).

## Screenshots and what was NOT verified (quality bar)
Looked at myself (headless engine, 1920x1200), in docs/handoffs/srvjoin-shots/:
- connecting1920.jpg: the engine's own fallback loading screen while connecting (no native front end): the status line sits at the
  bottom edge, no band. (This is the stock loading plaque; the Kotlin card is not drawn without the app.)
- home1920.jpg: the QuakeC UIB home, an early frame of a neighbouring screen, untouched by this branch (its render code is unchanged;
  only the "loading" kind got the dock block). Looks as it did before; not a regression I can see.
- feris2-sinister_full_r1-popup-picture.jpg and hightime-evilspace_r1-picture.jpg: the real map pictures read out of the servers' own packs by
  the Range path (Feris2 = Sinister, correct).
NOT verified (no emulator or Robolectric in this harness, and live joins were stopped by the permission classifier):
- The Android Kotlin join card, popup and placeholder were NOT rendered or screenshotted: no pixel test of the band, no 1920x1200 shot of the
  card, popup, art or cross-fade. JoinNoWedgeTest models the layers; everything visual on Android needs a look on the tablet.
- The QuakeC "loading" screen docking (cannot be entered headless).
- Hightime and Feris2 Join-to-picture timings after the fixes, and the "--for"-less early read against Hightime itself.
- Error paths run: probe with no answer returns [] within the timeout (ServerShotsTest), no cache dir / bad map name fetch nothing, 404/oversize/
  non-image pictures refused (MapShotsTest), a server ignoring Range is never read (ServerShotsTest, harvester self-test), a local game is
  unaffected (scripts/test-touch-local-join.sh: PASS), the stock server join and a csprogs download (join sequence, csqc test: PASS).

## Not done / next
- HTTP cache of popular csprogs.dat builds (race it with the in-protocol download): the only client-side cut of the 9 s;
  needs a published release and a CRC table. Preloading the world during the signon wait would hide about 1 s but
  Mod_FreeQ3Shaders at world load makes it unsafe. Confirm-before-big-download on metered not built (UX decision).
- No locks taken: the agent-locks tool could not see the repo from the worker's cwd.

## Tests
- JoinCardTest/JoinBarTest/JoinDetailTest/JoinDockAndDataTest (gradle testDebugUnitTest, local script srvjoin-gradle.sh).
- scripts/test-join-card-docking.sh (source-level guard), scripts/test-touch-join-csqc-download.sh (engine, local server).
Known: QC loading screen docking is not runtime-tested (a headless engine cannot enter its loading context).
