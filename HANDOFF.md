# Handoff: work/perf (faster loads, bundled stock CSQC, resumable asset fetch)

Branch `work/perf` (scratch clone `~/.cache/godnotic-work/cx-perf`), merged with origin/main c69ae1ae's base. Nothing is on main; no component-push was run.
Full numbers and test lines: `~/Projects/.coord/perf-report.md`.

## What the branch does
- `model_alias.c` / `model_shared.h` / `touch_loadtime.[ch]`: tangents of md3/md2/mdl animation frames are built on first draw, not for every frame at model load (`touch_load_lazytexvecs`, default 1; 0 = old behaviour; counter `morph texvec frames built` in LOADTIME). Local stormkeep first world frame 2883 -> 2359 ms (n=9), model load 1559 -> 969 ms.
- `gl_draw.c`: a pic whose load is running is returned as is, so the loading-screen redraw inside CL_KeepaliveMessage no longer decodes `gfx/loading` a second time. Menu 820 -> 744 ms (n=9).
- Stock CSQC for 0.8.5/0.8.6 bundled in the slim payloads (dlcache), first-join test, resumable/parallel desktop asset fetch (earlier commits, see report).
- Tests: `scripts/test-lazy-texvecs.sh`, `scripts/test-startup-loadingpic.sh`, `scripts/test-stock-csqc-join.sh`, `scripts/test-stock-csqc-cache.sh`, `scripts/test-asset-fetch-*.sh`.

## How to measure here
- Compare engines interleaved (`bench-load.sh -n 1` alternating old/new), n>=9, only when `uptime` load is low: other agents' builds swamped this host (load 100-280) and made wall times 3-4x slower and useless.
- perf: Flatpak SDK perf through its ld.so; `perf record --user-callchains --call-graph fp` on a build with `CFLAGS_EXTRA="-fno-omit-frame-pointer -g1"` (copy engine/darkplaces to another dir at the same depth; plain -g/dwarf are refused by this kernel's paranoid setting).
- `scripts/perf-guard.sh -n 5 -p 20-23 -b FILE -r` records, same without -r checks.

## Open
- Public autobuild csprogs CRCs unknown (needs a public join); DDS inflate/prefetch, CJK autohint at startup, config exec are the remaining startup/load costs.
- Not tried on any device.
