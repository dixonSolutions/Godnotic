# HANDOFF: work/hudaim (bigger health/armor HUD, predictive Devastator guide)

Branch work/hudaim (also pushed as ci/hudaim for the Android CI build). Based on origin/main, merged with origin/main 57876a46.
Engine and data are plain directories in this repo (no component repo commit made here; the coordinator runs component-push).

## Task 1: health and armor readout (DONE, verified headless on desktop)
- Both HUD copies changed in step: engine HUD (engine/darkplaces/touch_hud.c, `vitals_geometry`) and the port's CSQC HUD
  (qcsrc/client/touch_hud.qc). Shared maths: engine/darkplaces/touch_vitals.h.
- Size: the number is 40 dp tall, held between 6.0% and 8.5% of the short side (was 3.8%), the block is capped to 23.5% of the
  height and 26.5% of the width (46% in portrait). Health and armor are the big rows, ammo is a 0.7x row under them.
- Placement: top-left as before, clamped inside the screen and `_touch_safe_insets` plus a 6 dp margin.
- Colour cues kept: red at or under 25%, amber under 50%, green when full (num_color / HUD_Get_Num_Color); armor row dimmed at 0.
- Cvar: the existing `touch_hud_scale` (default 0.85, the settings "HUD size" slider) is the user's ratio to its default, so defaults are good untouched.
- The chat feed (and its icon row) moves down to y 0.30, height 0.12 (engine panel_place and Touch_ApplyChatFeedSettings), otherwise it collides with the bigger block.
- Test: `scripts/test-touch-hud.sh` (tests/touch-hud/vitals_test.c):
```
phone    2400x1080 dpi 400 con 1200.0x540.0 number  32.7 units =  6.1% of short side (old  3.8%),  4.2 mm tall; block 19.3%w x 23.5%h at (64,38)
tablet   2560x1600 dpi 274 con 1280.0x800.0 number  47.9 units =  6.0% of short side (old  3.8%),  8.9 mm tall; block 26.5%w x 23.2%h at (39,26)
web      1366x768  dpi  96 con 1366.0x768.0 number  46.1 units =  6.0% of short side (old  3.8%), 12.2 mm tall; block 23.9%w x 23.2%h at (46,30)
phone-p  1080x2400 dpi 400 con  540.0x1200.0 number  35.1 units =  6.5% of short side (old  3.8%),  4.5 mm tall; block 46.0%w x 11.3%h at (58,70)
small    1280x720  dpi 320 con  960.0x540.0 number  32.7 units =  6.1% of short side (old  3.8%),  3.5 mm tall; block 24.2%w x 23.5%h at (69,45)
vitals_test: all passed```
- Screenshots (headless, offscreen engine, local match with bots, /var/home/ratnode2/.cache/godnotic-work/hudaim-logs/):
  before (origin/main + botctl work, stock hud): before-phone.png (2400x1080), before-tablet.png (2560x1600), before-web.png (1366x768), before-tablet1200.png (1920x1200)
  after: after-phone.png, after-tablet.png, after-web.png, after-tablet1200.png (same names, "after-")
  I looked at after-tablet1200.png and after-phone.png: numbers and icons clearly bigger, no overlap with the chat icon row, top bar, radar or weapon list.
  (before-* is the botctl clone's build, which has the old HUD; the match differs by map position and bots.)
- `scripts/test-touch-controls-live.sh rects` (playing, spectating, scoreboard, dead, intermission, vote at tablet, phone, desk): all "intersections: none".

## Task 2: Devastator guiding (DONE as code + simulation; NOT verified in a real match)
- engine/darkplaces/touch_aim_guide.h: plans the meeting point from the target's filtered velocity and an acceleration estimate (fades over 0.45 s so a strafe reversal is not extrapolated), gravity for jumps, walls (CL_TraceLine clamps the predicted track), and the rocket's own speed, acceleration and turn rate (g_balance_devastator_speed/speedaccel/speedmax/guiderate/guidegoal read from cvars when the client has them, else the weapon table). The rocket flight along each candidate ray is simulated with TouchWep_GuideStep (the server's own steering), the least-miss candidate wins, with a sticky meeting time and a slew-limited low-passed aim point (no jumps or oscillation). Detonation at the planned closest approach when the blast reaches the target (existing rule kept as a fallback); never within the blast of the player. A target out of sight is advanced by its model for 0.6 s while fading, then let go.
- Wired into touch_aim.c steer_devastator (cvar cl_touch_guide_predict, default 1; 0 = old straight-line intercept). Group blast point, practice and path/waypoint logic of b9b7240d/27fd7ab0 are kept and still apply on top. Teammates: the target comes from the existing enemy-only scan and the mate_at_blast gate is unchanged, so teammates are never aimed at or blasted.
- Manual steering: while a guided weapon's fire is held with a rocket out (or auto-shoot steers), the look is eased to `cl_touch_guide_sens` (default 0.55) through a 0.12 s ramp, and the finger-speed gain is off (touch_hud.c look_cfg) so the same drag turns the same amount, no jumps.
- Test: scripts/test-touch-aim.sh runs tests/touch-aim/guide_test.c (rocket = server guidance, 60 launches per motion, targets 350-1450 units, blast radius 110). "old" is the pre-change logic (constant-velocity intercept + TouchWep_Detonate) reimplemented in the harness, flown against the same targets:
```
cost per call: velocity filter + confidence 137 ns (per player in view), break-out step 75 ns (once a frame)
touch aim predict: 117 checks OK
Devastator guiding: 60 launches per motion, targets 350-1450 units away; blast radius 110.
closest approach at detonation, units (mean / median / p90), share within the blast (82) and within a body (45)
motion    | old: constant-velocity intercept           | new: predictive guide                     
standing  |    59    58    68  blast 100.0% body   0.0% |     8     8    16  blast 100.0% body 100.0%
          view turning: jitter   0 ->   0 deg, reversals 0.0 -> 0.0, largest step 0.0 -> 0.0 deg/frame
strafing  |    70    69    89  blast  81.7% body   0.0% |    60    22    77  blast  93.3% body  73.3%
          view turning: jitter   5 ->  31 deg, reversals 0.0 -> 0.0, largest step 1.2 -> 4.2 deg/frame
jumping   |    69    67    91  blast  85.0% body   0.0% |    48    27   109  blast  90.0% body  81.7%
          view turning: jitter   4 ->  14 deg, reversals 0.0 -> 0.0, largest step 1.1 -> 4.5 deg/frame
circling  |    67    65    87  blast  81.7% body   5.0% |    39    17    72  blast  95.0% body  81.7%
          view turning: jitter   3 ->  16 deg, reversals 0.0 -> 0.0, largest step 1.5 -> 5.0 deg/frame
all motions: mean miss 66 -> 39 units; within the blast 87.1% -> 94.6%; within a body 1.2% -> 84.2%
strafing with a pillar 350 units out (rockets that hit it burst there): mean 245 -> 215 units, within the blast 50.0% -> 66.7%
33 checks, 0 failed```
  Caveats: the old logic is modelled in the harness, not the shipped binary; "within a body (45)" favours the new rule because the old one detonates at about half the blast radius by design. The simulation has no latency jitter and a perfect velocity feed.
- NOT verified: a real match. I ran a local Devastator-arena match with bots and cl_touch_aim_debug 3 (hudaim-logs/ab-pred0.log, ab-pred1.log) but auto-shoot never reached the guided-rocket state there (no look input), so there are no real rocket closest-approach numbers. The guide is verified only in the simulation and by compiling and running in a match.

## Not done / NOT verified
- Android emulator screenshots and real public servers (coordinator requests on 2026-11-23): NOT done, too little time given the heavy-slot queue. The bigger HUD is checked headless on desktop at 1920x1200, 2560x1600, 2400x1080, 1366x768 only. Tablet hardware, real touch via adb input, and tap-on-first-press were not checked. ci/hudaim is pushed so CI builds Android.
- Safe-area insets: clamped in code and unit tested, not seen with a real notch.
- Left-handed layout: vitals mirror through widget_center as before, but the chat panel move only applies to the right-handed layout in the engine path; left-handed not screenshotted.
- Portrait phone: unit tested, not screenshotted.

## Tests run (all in this clone)
- scripts/test-touch-aim.sh: pass (1133 checks + predict 117 + guide 33 checks, 0 failed).
- scripts/test-touch-hud.sh: pass. test-touch-aim-settings, altfire, boost, dodge, hook, controls, labels, scoreboard-portrait, hudmode: pass.
- scripts/test-touch-controls-live.sh rects: pass at tablet, phone, desk.
- scripts/test-touch-controls-live.sh local: FAIL "still mode=spectating". Pre-existing and unrelated: the test sets touch_always_autojoin 0 yet expects a straight-in join; not run against origin/main to confirm.
- QC compile (xonotic_compile_qc_only) and darkplaces-sdl: build OK. `make sv-release`: OK. `scripts/build-web.sh --engine-only` (XT_WEB_DEPS set): OK, 5.3M wasm.
- UI bridge tree not touched, so test-uibridge / loader-core cargo tests not run.

## Known issues
- test-touch-controls-live "local" case as above.
- hud_panel_chat_pos moved, so a user who saved their own chat position keeps it (seta re-applied only at touch init).
