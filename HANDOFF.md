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

## Real public servers (headless desktop client, offscreen; real SDL touch events through tests/touch-controls/finj.c)
Script: scripts/real-server-probe.sh <ip:port> <name> [tap] (D=<s> to wait longer for downloads). Servers from the master list
(master1/2/4.xonotic.org answered, 164 servers scanned with getstatus):
- 72.205.209.239:26000 "VHM 0.8.6 Server", stormkeep, stock game: joined and playing; strip = 2 weapon rows, hook=0 nade=0,
  `touch_hook_status` avail 0, `touch_nade_status` mutator 0: NO false detection (no HOOK entry, no NADE row).
  A real finger tap on row "1" while "2" was held switched the weapon on the FIRST tap (build/real-stock-tapup.png vs
  real-stock-strip.png, crop shown highlight moving to row 1).
- 66.42.118.210:26000 "(SMB) Votable Insta+Hook" (offhand hook, 0.8.5 autobuild): joined, strip = 1 weapon + HOOK entry
  (hook=1, avail 1, mutator 0: the hook is the offhand +hook, no nades). Real finger tap on the HOOK entry: hook held 1,
  buttons 32, player pulled (vel 533), row lit (build/real-hookinsta-tapdown.png). Seen in 1 of 4 runs (the insta server
  kills the player within seconds / changes map, so most runs hit a dead player: avail 0 is then correct).
  Observed once, NOT reproduced and unexplained: in one run the status 0.8 s after the finger lifted still said held 1;
  the synthetic release (_touch_test_finger) releases correctly. Needs a look if it shows up again.
- 45.33.24.39:26000 "Live Flags + Grapplehook" (CTF): custom pk3 downloads (~6 packs) still running after 26 s on this
  link; not waited out (metered link).
- Nades: no public server in the list advertises nades (no "nade" in any of the 164 hostnames or qcstatus mutators);
  the NADE row was exercised on my own stock dedicated server with g_nades 1 (test-touch-strip-live.sh, screenshots above)
  and on a local match, not on a public one.
Screens: build/real-stock-strip.png, real-stock-tapdown.png, real-stock-tapup.png, real-hookinsta-strip.png,
real-hookinsta-tapdown.png, real-hookinsta-tapup.png, real-hookctf-strip.png (loading).
Times (this machine, loaded, headless): connect to playing about 20 s on the stock server (4 MB csprogs download included);
list load from the masters about 10 s.

## NOT verified
- Android emulator / device screenshots: NOT done. The local debug APK build fails in this tree (the SDL Java glue org.libsdl.app
  is staged by scripts/android-build.sh, and the gradle step waited about an hour in the heavy.lock queue per attempt); I used
  the headless desktop client with real SDL touch events instead (engine HUD code is the same on Android).  Tablet touch by real fingers; thumb comfort of the row size
  (34 units action row, 26 units list rows at the tablet scale, about 1.9x px) is by geometry only.
- Hook weapon with fuel 0 (the faint EMPTY state) only by unit test, not live.
- Mouse-aiming/keyboard hook behaviour unchanged by design but not exercised.
- Web (wasm) and sv-release builds not run (no change to server code; engine HUD code is shared).
