# HANDOFF: smart DASH, predictive weapon boost (work/dash)

Branch `work/dash` (and `ci/dash` for the Android CI build). Not on main, no component push done.

## What it does
The DASH button (and the port's CSQC DASH, and `touch_dash [up|forward|auto] [weapon]`) is one smart tap:
- a plain dash (+dodge) when the server has dodging and it already gets the player where they go;
- a weapon boost (shot at the floor, a wall or behind: weapon jump) when that reaches a gap or ledge better,
  when a direction is forced, or when the server has no dodging and an open launch is the only dash;
- up or forward is worked out (move stick, view pitch, velocity, traces ahead/above: a gap with ground on the far side,
  a ledge or platform within reach, a ceiling), and every candidate (weapon x aim x with/without dodge) is predicted:
  blast point from the view ray, the server's push rule (`damage_explosion_calcpush`, `g_player_damageforcescale`),
  player motion while the switch and the projectile fly, the arc under gravity traced with the player's box;
- safety: self damage (armor first) against `cl_touch_dash_minhealth` (25) and `cl_touch_dash_maxself` (70); never in a menu,
  console, chat sheet, dead or spectating; no landing in a death volume, the void, or within 160 u of the map bottom;
  no open drop over 320 u; the weapon in hand preferred (switch delay counted);
- executes: switch weapon, turn the view, re-plan from the real state once the weapon is up, fire 0.1 s, turn back, take the
  old weapon back (auto-shoot/auto-switch stand down meanwhile);
- see and pick: a capsule above DASH ("Blaster up", "Devastator forward", "Dodge", "No boost"); long-press (0.35 s) opens chips Auto/Up/Forward and
  Auto + the boost weapons held (Devastator, Blaster, Electro, Hagar); slide and let go. A tap then acts on release (`cl_touch_dash_longpress 0` = act on press, no picker).
- Settings > Controls > Touch controls > Buttons (QC dialog and bridge): "Weapon boost on DASH", default ON (`cl_touch_dash_boost`).
  Off = exactly the old `+dodge`. All tunables are `cl_touch_dash_*` cvars (docs/CONTROLS.md, "Smart DASH").

What the client really reads: weapons held and ammo, health, armor, armor share (CSQC `armorblockpercent`), stick, view, map traces,
gravity/run/jump speed. On a server that is this machine (local game, hosted LAN) it also reads the weapons' damage/edge/radius/force/
speed and `g_balance_selfdamagepercent`, `g_player_damageforcescale`, `g_weaponforcefactor`, `g_balance_damagepush_speedfactor`, `g_dodging`,
dodge numbers. A remote server sends none of that, so stock numbers are used and scaled per weapon by the push each boost really gave
(`cl_touch_dash_learn`); three boosts in a row with no push turn boosting off for that server.
Dodging: the stock server does NOT announce dodging (its mutator list leaves it out, so the CSQC `active_mutators` bit 0 that
touch_hook.c relied on is never set). So: a local server says so itself; otherwise DASH tries a dash and watches whether it lifted the
player (dodge up speed 200): one that did = on, two that did nothing = off.

Files: `engine/darkplaces/touch_dashplan.h` (pure, new), `touch_dash.c/.h` (new), small hooks in `touch_hook.c` (init, frame,
buttons, InUse), `touch_hud.c` (DASH press/hold/release, `draw_dash_extras`), `makefile.inc`; QC: `touch_input.qc` (DASH sends
`touch_dash`), settings rows in `dialog_settings_touch.qc` and `touch_uib_touchcontrols.qc`; fixture `settings-touch.json`.
`touch_boost.h` (old straight-up decision) is kept, unchanged, unused.

## Tests and results (all run in this clone, engine built here)
- `scripts/test-touch-dash.sh`: 50 checks, 0 failed (gap crossing, ledge reach, low health, no-self-damage server, no dodging,
  weapon availability/ammo, custom balance, calibration, pit/bottom/drop guards, forced direction/weapon). Before: the suite does not
  build without `touch_dashplan.h` (`fatal error: touch_dashplan.h: No such file or directory`, shown). `test-touch-boost.sh` 42 ok,
  `test-touch-hook.sh` 88 ok, `test-touch-dodge.sh` 43 ok, `test-touch-aim.sh` 117 ok.
- `scripts/test-touch-dash-live.sh` (real dedicated server this machine, client offscreen through the finger shim): cases on/off/floor/ui/qc
  all passed in the final runs (log: ~/.cache/godnotic-work/dash-logs/live-final.log and live-final2.log).
  - dodging on server: status "unknown" (stock servers do not say), a tap with nothing to reach = a dodge that lifts the player,
    learned "dodging 1".
  - dodging off: two dashes do nothing, learned "dodging 0", the next tap boosts or says nothing, never dodges.
  - six forced boosts per server (Blaster and Devastator, up and forward): every one fired. Prediction vs where the player really came down
    (units, horizontal error / predicted travel; push measured / predicted):
    on:  blaster up 3/28 (1.03), blaster fwd 43/515 (0.99), devastator up 2/31 (1.05), devastator fwd 1/651 (1.05), blaster up 3/26, devastator fwd 98/642 (1.19)
    off: blaster up 0/25 (0.98), blaster fwd 155/637 (0.91), devastator up 2/30 (1.08), devastator fwd 241 horizontal + 249 vertical / 680 (1.02) = ONE MISS, blaster up 7/29, devastator fwd 62/656
    Up boosts land within ~10 u (vertical within 8). Forward boosts: median error ~7%; outliers 25-35% (air control and the
    stock-number assumptions are not modelled; one forward Devastator landed on another level). Check passes when at most one of six is outside
    max(130 u, 35% of travel); that one miss is real and listed.
  - health floor: `cl_touch_dash_minhealth 500` -> "tap: nothing (no safe boost)", nothing fired.
  - engine HUD on a foreign CSQC server: DASH tap/hold/slide/release with real finger events: picker opens (AUTO UP FORWARD, AUTO BLA), holding does
    not dash, sliding onto UP then BLA sets `cl_touch_dash_dir 1` / `cl_touch_dash_weapon blaster`, indicator reads "BLA UP" afterwards.
  - the port's CSQC HUD (local game): a finger tap on DASH reaches the smart dash.
- `scripts/test-touch-dash-real.sh` on REAL public servers (master list, stock 0.8.6 code and maps, ping ~267 ms, headless desktop client):
  - `66.226.73.30:27301` (Sonoran Arena, beginner FFA, fuse): joined, the FIRST finger tap on DASH acted (exactly one decision, no double fire),
    forced Blaster up: fired, planned landing -1806 14 -104, landed -1806 14 -117 (error 13, push 1.30 of predicted);
    Blaster forward toward a detected gap: fired, landed 6 u from the re-planned point (push 0.59 of the first prediction: the
    real server's numbers differ or the shot hit nearer; the calibration then scaled the Blaster to 0.92). Devastator: not owned there,
    correctly "no weapon". Screenshots below. Server had no dodging: the dash did nothing, learned after one.
  - `71.182.137.246:26100` (CA/CTF, 6 players): INCONCLUSIVE: the round had started, the client was dead/spectating when the script ran.
- `scripts/test-uibridge.sh`: all passed; `--engine`: engine_test, input_test, firstrun all passed. Its final `uib-fixtures.py --check`
  reports DRIFT on every settings fixture; that drift is there before this work too (same as work/botctl reported); I regenerated and kept
  only the new "Weapon boost on DASH" node in `settings-touch.json` (other drift left alone).
- `cargo test --offline -p godnotic-loader-core`: 20 + 13 passed.
- `make sv-release`: built. `scripts/build-web.sh --engine-only` (XT_WEB_DEPS set): built (5.5M wasm).

## Screenshots (1920x1200, headless, engine HUD on a foreign CSQC; LOOKED at each)
- ~/.cache/godnotic-work/dash-shots/dash_idle.png: DASH with its capsule above it (flat shapes: the gfx masks are missing in this offscreen basedir).
- ~/.cache/godnotic-work/dash-shots/dash_picker.png, dash_picker_hover.png: the picker open, chips AUTO/UP/FORWARD and AUTO/BLA, finger on a chip.
- ~/.cache/godnotic-work/dash-shots/dash_after_pick.png: after picking, the capsule reads "BLA UP".
- ~/.cache/godnotic-work/dash-shots/real_tap.png (real server, real masks), real_up_blaster.png, real_fwd_blaster.png, real_*_devastator.png.
Fixed from the first look: the capsule sat inside the stick area (moved above DASH), chips overlapped (spacing widened).

## Android emulator (real public server, real touch events) - DONE after the picker polish
Debug APK (x86_64, built after the merge with origin/main) on emulator-5580 under emu-slot.sh, game data pushed from local files, joined the real public server
`66.226.73.30:27301` (Sonoran Arena, stock maps, real ping) and played a match through the app. Frames (JPG <= 1500 px, all looked at): ~/.cache/godnotic-work/dash-shots-android/
- 1_capsule.jpg: the match with DASH and its capsule above it. 2_after_first_tap.jpg.
- 3_picker.jpg: after `adb shell input motionevent DOWN 317 576` and a 1.2 s hold: the picker open (rings on Auto/Auto, Blaster, Up, Forward; readable names; clear gap to the chat-log icons).
- 4_finger_on_up.jpg: the finger moved (motionevent MOVE x6) onto the Up chip, read from the console AFTER the picker opened (Up chip at 439 450): Up filled. 4b_after_pick.jpg: capsule "Blaster up".
- 5a_boost_rising.jpg, 5b_boost_midair.jpg (health 87), 6_after_landing.jpg (health 88, on the upper platform). 0_after_prompt/0b_dialogs_gone: the first-run prompts dismissed.
Console (console-excerpt.txt, console-final.txt): the first `input tap` = exactly one `tap: dodge` decision (this server has no dodging); the long press = no tap line; `picked Up (dir 1)`;
then ONE tap on DASH = `tap: boost (open launch up)` -> `boost: blaster up ... predicted landing -242 -971 80` -> `fired blaster` -> `landed: from -267 -984 80 landed -421 -1063 384 predicted -339 -1049 384 error 83 (horizontal 83, vertical 0)`:
it fired on the first tap, no misfire, and landed on the platform 304 units higher (the real server's push; health 100 -> 87).

## NOT verified / not done
- The native Kotlin/GTK settings screens: only the bridge fixture and the QC dialog source changed; no screenshot of the native Settings page
  (the QC menu dialog and bridge tree were not rendered to a picture).
- The desktop real-server runs were headless desktop; the Android run (above) is the real-touch one. Only Blaster was available on those servers; the Devastator was verified on a local server only.
- Remote servers' balance is not read (not sent); only learned. A modified balance on a remote server is corrected after one boost.
- The CSQC (port) HUD has no capsule or picker; its DASH sends the same tap with the saved choices.
- A look drag during the ~0.5 s boost is not kept. Air control after the blast is not modelled. Dodge numbers on a remote server are the
  stock ones.
## Known bugs / neighbours
- Server list status (coordinator follow-up, fixed): `TouchSrvInfo_PlayerDetails` copied the name from its opening quote to the END of the whole
  status (`dp_strlcpy`), so every long status warned "src string unterminated or truncated" through Con_Printf (spam, and it also interleaved with
  `fe_dump` JSON). It now copies exactly the bytes between the quotes (memcpy, clamped, NUL). A second source of the same warning was
  `netconn.c` copying a busy server's whole player block into `players[2800]`; that now goes through `TouchNet_CopyPlayerBlock` (touch_net.c):
  whole lines only, no warning. Test: `tests/srvinfo/test_frontend_players.py` has a second fake server with 45 long colour-coded players
  (a 3 KB status): 35 whole rows listed, no truncation warning, process exits 0 (it failed on the old code through the warning assertion).
  THE SEGFAULT: I could not reproduce it again. On the old code the real-master repro (`net_slist`, `slist`, quit) ran 5 more times with rc 0
  and 6-12 warnings each; with the fix, 3 runs rc 0 and 0 warnings. The crash I saw first was most likely my own: a second client started on the
  same userdir while the first was still running (its log had 67 lines). The stock-server crash (dedicated, SV_Frame) was a server started
  on a basedir whose pk3 links dangled. No out-of-bounds write exists in PlayerDetails (players[128] is capped, names clamped to 127).
- The stock-data flatpak the coordination notes mention is gone (`~/.local/share/flatpak/.../xonotic/data` missing, `godnotic-audit/base` pk3 links
  dangle), so the stock-server suites cannot run; I used the 20261107 packs plus the audit progs as a foreign-CSQC server.
- Forward Devastator boost after another-level landing: one MISS in the numbers above.

## Picker polish after the coordinator's look at the Android frames (this commit)
- The choice in force is a RING round its chip (the old pip overlapped "Auto"); a finger over a chip fills it.
- Chips are as wide as their text plus a margin (no more "FORWARD" touching its edges); readable names (Auto, Up, Forward, Devastator,
  Blaster, Electro, Hagar) instead of BLA/DEV; the indicator capsule reads "Blaster up" / "Devastator forward" / "Dodge" / "No boost" and widens to its text.
- The picker's left edge stays right of the chat-log icon row (>= 14% of the screen width) and its rows are a full chip apart.
- The four small trash/copy/download/share icons are NOT mine: they are the chat-log actions from `engine/darkplaces/touch_chatlog.c` (main, chat work:
  commits 20ca67d9 "Chat: live typing line, @mention picker..., chat log with fade, scroll-back and export", 25015175 "Chat log icons: dock on the chat box",
  9ff1af6d "Chat polish: crisp pixel-size log icons hidden on an empty log", 4bfede6d "Chat log icons: draw the pixel-size art as runs of device pixels").
  They show whenever the chat log holds a line (`TouchChatLog_IconsShown`), and a live match always puts lines in it (join, flag and kill messages), so they sit in
  the top-left under the health panel for the whole match. Left as they are.
- Desktop re-run after the merge with origin/main: dash 50/50, boost 42, hook 88, dodge 43; live suite on/off/floor/ui/qc all passed (6 forced boosts each,
  one MISS in the "on" run: Blaster up, 257 units below the predicted level); srvinfo test passed; uibridge: engine_test, input_test, firstrun passed (settings fixture DRIFT is
  there before this work); cargo 23 + 13; `make sv-release` and `build-web.sh --engine-only` built; debug APK built.

## Square backing removed and picker rows captioned (final polish)
- The square behind DASH (solid blue while held, faint dark when idle) came from the soft shade/glow MASK pictures drawing as solid squares behind every round button
  where their alpha is lost (Android, offscreen). `shape_shadow`, `shape_glow` and the capsule shade in `touch_hud.c` are now stacked discs (`DrawQ_RoundedBox`), no masks:
  DASH, FIRE, ALT, TURN, DUCK and the chips are round with a round pressed fill.
- The two picker rows are captioned "Weapon" and "Direction" (tiny text above each row, inside the left-edge gap) and spaced a chip apart.
- Android recheck (emulator-5580 via emu-slot.sh, noupdate + netwait, real server 66.226.73.30:27301, real motionevents): ~/.cache/godnotic-work/dash-shots-android2/
  1_capsule.jpg (idle: round DASH, "Dodge" capsule, no square), 3_picker.jpg (held, picker open, captions, round blue fill), 4_finger_on_up.jpg (finger on Up),
  console: `picked Up (dir 1)`. The frames after the pick (4b onward) show the death screen: a real bot fragged the player in this live match before the boost tap, so
  there is no new boost frame in this run; the boost on Android is the previous run's evidence (dash-shots-android/: it fired on the first tap and landed 304 units up).
