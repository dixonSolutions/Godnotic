# savecvars: Duel bots, recent games and LAN create saved across restarts

## Done and verified
- `touch_lan_bots_duel` and `_uib_recent` (touch_uib_screens.qc) are registered with 32
  (CF_ARCHIVE) instead of 1 (CF_CLIENT). `_uib_recent` is also registered in `UIB_Init`.
- `touch_lan_create` and `touch_lan_create_password` (dialog_touch_lan.qc) were
  `CVAR_SAVE`, which is 1 in QC and so CF_CLIENT in the engine. Now 32.
- `scripts/test-saved-cvars.sh`: sets the two cvars, quits, restarts on the same userdir,
  reads them back, checks config.cfg. Passes on the fix; fails on the flag-1 build.
- Menu QC compiles with gmqcc (menu.dat, csprogs.dat, progs.dat).

## Not done or not tested
- The two LAN create checkboxes are fixed but not covered by the test: their registration
  runs when the Create screen opens, so a scripted `set` before that would not reach it.
- `touch_update_auto` (flag 0) is left alone on purpose: its comment says the platform
  layer persists it.
- engine/data is a component (Godnotic-data): the data pin in components.json is not
  updated. The coordinator needs `scripts/component-push.sh data` before this merges to main.
- Android and the tablet were not tested.

## Test commands and results
- `taskset -c 0-3 bash scripts/test-saved-cvars.sh`: 5 PASS, exit 0.
- `taskset -c 0-3 bash scripts/test-uibridge.sh`: all passed, exit 0 (no --engine).
- `taskset -c 0-3 bash scripts/test-old-name.sh`: PASS, exit 0.
