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

## Not done / next
- HTTP cache of popular csprogs.dat builds (race it with the in-protocol download): the only client-side cut of the 9 s;
  needs a published release and a CRC table. Preloading the world during the signon wait would hide about 1 s but
  Mod_FreeQ3Shaders at world load makes it unsafe. Confirm-before-big-download on metered not built (UX decision).
- No locks taken: the agent-locks tool could not see the repo from the worker's cwd.

## Tests
- JoinCardTest/JoinBarTest/JoinDetailTest/JoinDockAndDataTest (gradle testDebugUnitTest, local script srvjoin-gradle.sh).
- scripts/test-join-card-docking.sh (source-level guard), scripts/test-touch-join-csqc-download.sh (engine, local server).
Known: QC loading screen docking is not runtime-tested (a headless engine cannot enter its loading context).
