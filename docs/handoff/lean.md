# HANDOFF: work/lean (launch, local start, in-match weight)

## Done and verified
- Measured (see docs/PERFORMANCE.md "Third pass"): menu 558-610 ms, cold Play ~2.85 s, warm Play ~0.9-1.1 s,
  in-match mean frame 2.3 ms / p95 3.2 ms (4 bots, offscreen Mesa), peak RSS ~700 MB. The earlier passes already
  took the big start-up costs; the remaining ones are listed there with their sizes.
- `FRAMETIME_MEM` line (touch_frametime.h): process RSS and peak, Linux/Android.
- `scripts/perf-guard.sh` has a `match` scenario (frame mean, p95, peak RSS) with per-metric slack.
- `touch_skin_skipgloss` (default 1): `_gloss` maps not read while `r_shadow_gloss` is 0; battery and thermal profiles
  set it. stormkeep bytes loaded 286.8 -> 253.6 MB with gloss off. Default (gloss on) profile is unchanged.

## Not done / findings for others
- No real launch or local-start win landed on the default profile: this pass found nothing left that is both big and safe.
- Biggest remaining memory: QuakeC edict storage (server 107 MB, CSQC 38 MB, menu 30 MB at 5494/2646/2720 fields per
  edict). Needs fewer fields in the QC compile, or not building the QC menu tree when a native UI is attached (menu.dat
  pool is 52 MB, 2816 widget edicts; the native-UI owners should decide).
- `svs.clients` is 12.4 MB for 16 slots (client_t ~775 KB each) however many play; a smaller `maxplayers` for local games
  lives in the create dialog (another worker's files).
- unifont.ttf (12 MB) is inflated at start for the fallback face (43 ms); making fallback faces lazy touches ft2.c glyph
  lookup (4 places) and was not attempted.
- Not measured: Android emulator or the tablet (no APK built; Android builds only in CI), cold page cache.
- The perf-guard baseline is machine-specific; none is committed.

## Tests
See the final report; commands: `scripts/perf-guard.sh -n 3 -r` then `scripts/perf-guard.sh -n 3`,
`bash scripts/test-startup-loadingpic.sh`, `make sv-release`, `scripts/build-web.sh --engine-only`.
