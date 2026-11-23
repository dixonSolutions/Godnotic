# HANDOFF: work/wpnhook (HOOK in the weapon menu, nade/hook hit-testing, nades in the sidebar)

Branch work/wpnhook (also pushed as ci/wpnhook), origin/main merged in.

## Root cause: nade icon triggering the hook
The strip hit-test padded every weapon row 12 % on all four sides, and the NADE row was only tested while a
grenade was ready. With the NADE row "waiting" for its next grenade (the default 6 s after every throw), a finger on
its top edge fell into the padded bottom of the weapon row above, which with the Hook weapon (impulse 0, last in
the list) selected the hook. Both HUD copies (engine `touch_hud.c`, port CSQC `touch_weapons.qc`) had the same
rule. Also +hook (button6) is shared by the nade and the offhand hook, and nothing stopped the two being held at once.

## What changed
- engine/darkplaces/touch_strip.h (new, pure geometry): one band per finger, shared edges, forgiveness sideways and
  upward over the first row only; a row that exists but cannot be pressed (waiting nade, hook without fuel) and the
  gap swallow the finger (TSTRIP_DEAD). Used by touch_hud.c; mirrored in touch_weapons.qc.
- HOOK is an entry of the weapon strip (after the last weapon, Hook weapon icon, "H" in the number column); hold
  = hook, drag steers. It replaces the Hook weapon's own row. The separate HOOK button, its layout-editor widget,
  circle hit/draw and settings radio "Always" are gone. Settings: "Hook in weapon bar" Off/On.
- NADE row: taller (34 against 26 units) after a 6-unit gap, i.e. an action area of the sidebar (the weapon strip is
  the side rail; the nade was already on it, now it is separated and thumb sized). No other quick action needed for
  the hook. Nade and hook never share a finger: while one is held the other takes no touch; the hook hold also
  stands down while the grenade is held.
- Old layouts: touch_hook_visible 0 stays off, 1 and 2 mean on (1 is rewritten to 2); touch_hook_x/_y/_size stay
  registered but are ignored. Row not tied to a mouse being present.
- Test hook `_touch_test_strip` (engine) drives fingers through the real finger table.
- docs/CONTROLS.md, docs/SETTINGS_MAP.md, scripts/audit-touch-ergonomics.py updated.

## Tests (all run on this clone's build, post-change engine; QC compiled -Werror)
- scripts/test-touch-strip.sh: 254 checks 0 failures (hit-test: every band, edges, sweeps through the NADE band,
  waiting nade swallows, hook without fuel, no-hook/no-nade variants, the old rule reproducing the bug, weapon list
  holds the hook, old-layout mapping) plus layout checks (HOOK widget gone, old cvars still load): PASS.
- scripts/test-touch-strip-live.sh (stock server, engine HUD, fingers through finger table): 10/10 ok: NADE row
  never hooks, HOOK entry never throws, gap dead, waiting NADE top and bottom swallowed, weapon row neither, two
  fingers do not mix in either order. PASS.
- touch-hook, touch-spectate, touch-hudmode, touch-controls, touch-labels, touch-profiles, touch-dodge, touch-ledge,
  touch-altfire, touch-aim: PASS. touch-controls-live `rects` (stock server, tablet/phone/desk, 6 states, no
  control intersections): 21 ok 0 FAIL.
- touch-controls-live `local`: FAIL, but it fails identically on srvjoin's build (pre-existing: the stock Flatpak
  0.8.6 data is gone, I used the 20261107 data; the local player stays spectating).

## Screenshots (1920x1200, headless; I looked at each; copies in ~/.cache/godnotic-work/wpnhook-shots/)
build/touch-strip-nade-hookweapon.png (nades server, Hook weapon: weapons, H HOOK row, gap, NADE row),
touch-strip-offhand-hook.png (g_grappling_hook only: H row, no NADE row), touch-strip-plain.png (no hook, no nades:
unchanged strip), touch-strip-oldlayout-hook1.png (old touch_hook_visible 1 + old x/y/size: entry on),
touch-strip-oldlayout-hook0.png (old 0: no entry, Hook weapon is a normal row again), touch-strip-live-hook-held.png
(HOOK lit while held), touch-strip-live-nade-held.png (NADE lit, HOOK dim), touch-strip-live-waiting.png (waiting NADE
faint), touch-strip-local-csqc.png and touch-strip-local-csqc-offhand.png (the port's CSQC HUD in a local match).
Regenerate: scripts/shot-touch-strip.sh. (build/ is git-ignored.)

## NOT verified
- Android emulator / device screenshots (see below). Tablet touch by real fingers; thumb comfort of the row size
  (34 units action row, 26 units list rows at the tablet scale, about 1.9x px) is by geometry only.
- Hook weapon with fuel 0 (the faint EMPTY state) only by unit test, not live.
- Mouse-aiming/keyboard hook behaviour unchanged by design but not exercised.
- Web (wasm) and sv-release builds not run (no change to server code; engine HUD code is shared).
